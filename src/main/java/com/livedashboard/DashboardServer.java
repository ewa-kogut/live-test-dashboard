package com.livedashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Receives events from test runs, saves each run to its own file and streams events to open browser
 * pages. It listens on this computer only, because failure messages can contain test data.
 */
public final class DashboardServer {

  static final int DEFAULT_MAX_RUNS = 100;

  private static final int DEFAULT_PORT = 8095;
  private static final int MAX_EVENT_BYTES = 5 * 1024 * 1024;
  private static final String RESET_EVENT = "{\"type\":\"reset\"}";
  private static final String RUN_FILE_SUFFIX = ".jsonl";
  /** Run IDs become file names, so only these characters are allowed: no dots, no slashes. */
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private static final Set<String> LOCAL_HOST_NAMES = Set.of("localhost", "127.0.0.1", "[::1]");

  private static final Pattern RUN_EVENTS_PATH = Pattern.compile("/api/runs/([^/]+)/events");
  private static final Map<String, StaticFile> STATIC_FILES =
      Map.of(
          "/", new StaticFile("/web/index.html", "text/html; charset=utf-8"),
          "/app.js", new StaticFile("/web/app.js", "text/javascript; charset=utf-8"),
          "/style.css", new StaticFile("/web/style.css", "text/css; charset=utf-8"));

  private final HttpServer server;
  private final ScheduledExecutorService heartbeat;
  private final Path dataDirectory;
  private final int maxRuns;
  private final List<ClientStream> streams = new ArrayList<>();
  private String latestRunId;

  private record StaticFile(String resource, String contentType) {}

  /** A page's open stream; a null run ID means it follows whichever run is the latest. */
  private record ClientStream(OutputStream output, String runId) {}

  public DashboardServer(int port, Path dataDirectory) throws IOException {
    this(port, dataDirectory, DEFAULT_MAX_RUNS);
  }

  DashboardServer(int port, Path dataDirectory, int maxRuns) throws IOException {
    this.dataDirectory = dataDirectory;
    this.maxRuns = maxRuns;
    Files.createDirectories(dataDirectory);
    List<Path> savedRuns = runFilesNewestFirst();
    latestRunId = savedRuns.isEmpty() ? null : runId(savedRuns.get(0));
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/api/events", localOnly(this::handleEvent));
    server.createContext("/api/stream", localOnly(this::handleStream));
    server.createContext("/api/runs", localOnly(this::handleRuns));
    server.createContext("/", localOnly(this::handleStaticFile));
    heartbeat =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "live-test-dashboard-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
  }

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
    Path dataDirectory = configuredDataDirectory();
    DashboardServer dashboard = new DashboardServer(port, dataDirectory);
    dashboard.start();
    System.out.println("Live Test Dashboard: http://localhost:" + dashboard.port());
    System.out.println("Saved runs: " + dataDirectory);
  }

  public void start() {
    server.start();
    heartbeat.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
  }

  public void stop() {
    heartbeat.shutdownNow();
    server.stop(0);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private static Path configuredDataDirectory() {
    String configured = System.getProperty("dashboard.data.dir");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured.trim());
    }
    return Path.of(System.getProperty("user.home"), ".live-test-dashboard", "runs");
  }

  /**
   * Answers only requests addressed to this computer by name. Listening on 127.0.0.1 is not enough
   * on its own: a web page can point its own domain at 127.0.0.1 (DNS rebinding) and then read the
   * saved runs as if it were the dashboard. Such a request still carries that domain in its Host
   * header, so it is refused here.
   */
  private static HttpHandler localOnly(HttpHandler handler) {
    return exchange -> {
      if (isLocalHost(exchange.getRequestHeaders().getFirst("Host"))) {
        handler.handle(exchange);
      } else {
        try (exchange) {
          respond(exchange, 403);
        }
      }
    };
  }

  /** True for "localhost", "127.0.0.1" and "[::1]", with or without a port. */
  static boolean isLocalHost(String host) {
    if (host == null) {
      return false;
    }
    String name =
        host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.split(":", 2)[0];
    return LOCAL_HOST_NAMES.contains(name.toLowerCase(Locale.ROOT));
  }

  private void handleEvent(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405);
        return;
      }
      // Web pages cannot send this content type here without a CORS approval, which never comes.
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      if (contentType == null || !contentType.startsWith("application/json")) {
        respond(exchange, 415);
        return;
      }
      String runId = exchange.getRequestHeaders().getFirst("X-Run-Id");
      if (runId == null || !RUN_ID.matcher(runId).matches()) {
        respond(exchange, 400);
        return;
      }
      byte[] body = exchange.getRequestBody().readNBytes(MAX_EVENT_BYTES + 1);
      if (body.length > MAX_EVENT_BYTES) {
        respond(exchange, 413);
        return;
      }
      publish(runId, new String(body, StandardCharsets.UTF_8));
      respond(exchange, 204);
    }
  }

  private void handleStream(HttpExchange exchange) throws IOException {
    String requestedRun = runFromQuery(exchange.getRequestURI().getQuery());
    if (!"GET".equals(exchange.getRequestMethod())
        || (requestedRun != null && !isSavedRun(requestedRun))) {
      try (exchange) {
        respond(exchange, "GET".equals(exchange.getRequestMethod()) ? 404 : 405);
      }
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, 0);
    OutputStream output = exchange.getResponseBody();
    synchronized (this) {
      // A page first receives what it missed. The exchange stays open on purpose.
      String replayedRun = requestedRun != null ? requestedRun : latestRunId;
      if (replayedRun != null) {
        for (String event : Files.readAllLines(runFile(replayedRun), StandardCharsets.UTF_8)) {
          write(output, format(event));
        }
      }
      output.flush();
      streams.add(new ClientStream(output, requestedRun));
    }
  }

  private void handleRuns(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if ("/api/runs".equals(path)) {
        sendRunList(exchange);
        return;
      }
      Matcher matcher = RUN_EVENTS_PATH.matcher(path);
      if (!matcher.matches() || !isSavedRun(matcher.group(1))) {
        respond(exchange, 404);
        return;
      }
      byte[] content;
      synchronized (this) {
        content = Files.readAllBytes(runFile(matcher.group(1)));
      }
      send(exchange, content, "application/x-ndjson; charset=utf-8");
    }
  }

  private void sendRunList(HttpExchange exchange) throws IOException {
    List<Object> runs = new ArrayList<>();
    synchronized (this) {
      for (Path file : runFilesNewestFirst()) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", runId(file));
        run.put("updatedAt", Files.getLastModifiedTime(file).toMillis());
        runs.add(run);
      }
    }
    send(exchange, Json.write(runs).getBytes(StandardCharsets.UTF_8), "application/json");
  }

  private void handleStaticFile(HttpExchange exchange) throws IOException {
    try (exchange) {
      StaticFile file = STATIC_FILES.get(exchange.getRequestURI().getPath());
      if (file == null || !"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, file == null ? 404 : 405);
        return;
      }
      try (InputStream resource = DashboardServer.class.getResourceAsStream(file.resource())) {
        send(exchange, resource.readAllBytes(), file.contentType());
      }
    }
  }

  private synchronized void publish(String runId, String event) throws IOException {
    String line = event.replaceAll("[\\r\\n]+", " ");
    boolean isNewRun = !Files.exists(runFile(runId));
    Files.writeString(
        runFile(runId),
        line + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    if (isNewRun) {
      latestRunId = runId;
      deleteOldestRuns();
      broadcast(format(RESET_EVENT), stream -> stream.runId() == null);
    }
    broadcast(
        format(line),
        stream -> stream.runId() == null ? runId.equals(latestRunId) : stream.runId().equals(runId));
  }

  private void deleteOldestRuns() throws IOException {
    List<Path> runs = runFilesNewestFirst();
    for (Path old : runs.subList(Math.min(maxRuns, runs.size()), runs.size())) {
      // Never remove the run that has just started, even if file times are equal.
      if (!runId(old).equals(latestRunId)) {
        Files.deleteIfExists(old);
      }
    }
  }

  private List<Path> runFilesNewestFirst() throws IOException {
    try (Stream<Path> files = Files.list(dataDirectory)) {
      return files
          .filter(file -> file.getFileName().toString().endsWith(RUN_FILE_SUFFIX))
          .sorted(Comparator.comparing(DashboardServer::modifiedTime).reversed())
          .toList();
    }
  }

  private static FileTime modifiedTime(Path file) {
    try {
      return Files.getLastModifiedTime(file);
    } catch (IOException e) {
      return FileTime.fromMillis(0);
    }
  }

  private boolean isSavedRun(String runId) {
    return RUN_ID.matcher(runId).matches() && Files.exists(runFile(runId));
  }

  private Path runFile(String runId) {
    return dataDirectory.resolve(runId + RUN_FILE_SUFFIX);
  }

  private static String runId(Path file) {
    String name = file.getFileName().toString();
    return name.substring(0, name.length() - RUN_FILE_SUFFIX.length());
  }

  private static String runFromQuery(String query) {
    if (query == null) {
      return null;
    }
    for (String parameter : query.split("&")) {
      if (parameter.startsWith("run=")) {
        return parameter.substring("run=".length());
      }
    }
    return null;
  }

  private synchronized void sendHeartbeat() {
    broadcast(": keep-alive\n\n", stream -> true);
  }

  private void broadcast(String message, java.util.function.Predicate<ClientStream> receives) {
    Iterator<ClientStream> iterator = streams.iterator();
    while (iterator.hasNext()) {
      ClientStream stream = iterator.next();
      if (receives.test(stream)) {
        try {
          write(stream.output(), message);
          stream.output().flush();
        } catch (IOException e) {
          iterator.remove();
        }
      }
    }
  }

  private static void write(OutputStream stream, String message) throws IOException {
    stream.write(message.getBytes(StandardCharsets.UTF_8));
  }

  private static String format(String event) {
    return "data: " + event + "\n\n";
  }

  private static void send(HttpExchange exchange, byte[] content, String contentType)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, content.length == 0 ? -1 : content.length);
    if (content.length > 0) {
      exchange.getResponseBody().write(content);
    }
  }

  private static void respond(HttpExchange exchange, int status) throws IOException {
    exchange.sendResponseHeaders(status, -1);
  }
}
