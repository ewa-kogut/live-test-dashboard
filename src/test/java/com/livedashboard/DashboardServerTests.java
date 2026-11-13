package com.livedashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class DashboardServerTests {

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  @TempDir Path dataDirectory;
  private DashboardServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = new DashboardServer(0, dataDirectory);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  @DisplayName("A page opened later receives the events it missed")
  void shouldReplayEventsToNewStream() throws Exception {
    // Arrange
    assertEquals(204, postEvent("run-1", "{\"type\":\"run-started\"}").statusCode());

    // Act
    List<String> received = readStreamEvents(1);

    // Assert
    assertEquals(List.of("data: {\"type\":\"run-started\"}"), received);
  }

  @Test
  @DisplayName("A new run tells open pages to reset and drops the previous run")
  void shouldResetOnNewRun() throws Exception {
    // Arrange
    postEvent("run-1", "{\"n\":1}");

    // Act
    postEvent("run-2", "{\"n\":2}");
    List<String> received = readStreamEvents(1);

    // Assert
    assertEquals(List.of("data: {\"n\":2}"), received);
  }

  @Test
  @DisplayName("A line break in an event cannot break the stream format")
  void shouldRemoveLineBreaksFromEvents() throws Exception {
    // Arrange
    postEvent("run-1", "{\"a\":\n1}");

    // Act
    List<String> received = readStreamEvents(1);

    // Assert
    assertEquals(List.of("data: {\"a\": 1}"), received);
  }

  @Test
  @DisplayName("Keeps every run, so an older run can still be opened")
  void shouldKeepEachRunSeparately() throws Exception {
    // Arrange
    postEvent("run-1", "{\"n\":1}");
    postEvent("run-2", "{\"n\":2}");

    // Act
    List<String> firstRun = readStreamEvents("/api/stream?run=run-1", 1);
    HttpResponse<String> firstRunEvents = get("/api/runs/run-1/events");

    // Assert
    assertEquals(List.of("data: {\"n\":1}"), firstRun);
    assertEquals("{\"n\":1}\n", firstRunEvents.body());
  }

  @Test
  @DisplayName("Lists the saved runs")
  void shouldListSavedRuns() throws Exception {
    // Arrange
    postEvent("run-1", "{}");

    // Act
    HttpResponse<String> runs = get("/api/runs");

    // Assert
    assertEquals(200, runs.statusCode());
    assertTrue(runs.body().contains("\"id\":\"run-1\""), "The list should name the run");
  }

  @Test
  @DisplayName("Saved runs are still there after a server restart")
  void shouldKeepRunsAfterRestart() throws Exception {
    // Arrange
    postEvent("run-1", "{\"n\":1}");
    server.stop();
    server = new DashboardServer(0, dataDirectory);
    server.start();

    // Act
    List<String> latest = readStreamEvents(1);

    // Assert
    assertEquals(List.of("data: {\"n\":1}"), latest);
  }

  @Test
  @DisplayName("Deletes the oldest runs above the limit, but never the newest")
  void shouldDeleteOldestRunsAboveLimit() throws Exception {
    // Arrange
    server.stop();
    server = new DashboardServer(0, dataDirectory, 2);
    server.start();

    // Act
    postEvent("run-1", "{}");
    Thread.sleep(50);
    postEvent("run-2", "{}");
    Thread.sleep(50);
    postEvent("run-3", "{}");

    // Assert
    assertEquals(404, get("/api/runs/run-1/events").statusCode());
    assertEquals(200, get("/api/runs/run-3/events").statusCode());
  }

  @Test
  @DisplayName("Rejects run IDs that could reach outside the data folder")
  void shouldRejectUnsafeRunIds() throws Exception {
    // Arrange – the ID becomes a file name

    // Act
    int event = postEvent("..-..evil.txt", "{}").statusCode();
    int stream = get("/api/runs/..%2F..%2Fevil/events").statusCode();

    // Assert
    assertEquals(400, event);
    assertEquals(404, stream);
  }

  @Test
  @DisplayName("Rejects events without the JSON content type, which web pages could send")
  void shouldRejectWrongContentType() throws Exception {
    // Arrange
    HttpRequest request =
        request("/api/events")
            .header("Content-Type", "text/plain")
            .header("X-Run-Id", "run-1")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

    // Act
    int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();

    // Assert
    assertEquals(415, status);
  }

  @Test
  @DisplayName("Rejects events without a run ID")
  void shouldRejectMissingRunId() throws Exception {
    // Arrange
    HttpRequest request =
        request("/api/events")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

    // Act
    int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();

    // Assert
    assertEquals(400, status);
  }

  @Test
  @DisplayName("Serves the page, and nothing outside the known files")
  void shouldServeOnlyKnownFiles() throws Exception {
    // Arrange – the server was started in startServer()

    // Act
    HttpResponse<String> page = get("/");
    HttpResponse<String> unknown = get("/../pom.xml");

    // Assert
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("Live Test Dashboard"), "The page should name the tool");
    assertEquals(404, unknown.statusCode());
  }

  @Test
  @DisplayName("Refuses requests for another host name, as sent after DNS rebinding")
  void shouldRejectForeignHostHeader() throws Exception {
    // Arrange
    postEvent("run-1", "{}");

    // Act
    String foreign = statusLineFor("evil.example:" + server.port());
    String local = statusLineFor("127.0.0.1:" + server.port());

    // Assert
    assertTrue(foreign.contains(" 403 "), "A foreign host should get 403, got: " + foreign);
    assertTrue(local.contains(" 200 "), "127.0.0.1 should still work, got: " + local);
  }

  @Test
  @DisplayName("Treats only this computer's names as local")
  void shouldRecogniseLocalHostNames() {
    // Arrange – the Host header values a browser or HttpClient sends

    // Act and Assert
    assertTrue(DashboardServer.isLocalHost("localhost:8095"));
    assertTrue(DashboardServer.isLocalHost("LOCALHOST"));
    assertTrue(DashboardServer.isLocalHost("127.0.0.1:8095"));
    assertTrue(DashboardServer.isLocalHost("[::1]:8095"));
    assertFalse(DashboardServer.isLocalHost("evil.example:8095"));
    assertFalse(DashboardServer.isLocalHost("localhost.evil.example"));
    assertFalse(DashboardServer.isLocalHost(null));
  }

  /** HttpClient does not allow setting Host, so this sends the request over a plain socket. */
  private String statusLineFor(String host) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      String request = "GET /api/runs HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
          .readLine();
    }
  }

  private HttpResponse<Void> postEvent(String runId, String json) throws Exception {
    HttpRequest request =
        request("/api/events")
            .header("Content-Type", "application/json")
            .header("X-Run-Id", runId)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private List<String> readStreamEvents(int count) throws Exception {
    return readStreamEvents("/api/stream", count);
  }

  private List<String> readStreamEvents(String path, int count) throws Exception {
    HttpResponse<InputStream> response =
        client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    List<String> received = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      while (received.size() < count) {
        String line = reader.readLine();
        if (line != null && line.startsWith("data: ")) {
          received.add(line);
        }
      }
    }
    return received;
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
        .timeout(Duration.ofSeconds(10));
  }
}
