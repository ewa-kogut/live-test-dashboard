package com.livedashboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends events in order on a background thread. A dashboard problem must never affect the tests, so
 * after the first failure it prints one line and stops sending.
 */
final class EventSender {

  private static final String DEFAULT_URL = "http://localhost:8095";
  private static final long FLUSH_TIMEOUT_SECONDS = 5;

  private final String runId;
  private final URI eventsUri;
  private final HttpClient client;
  private final ExecutorService executor;
  private volatile boolean enabled;

  EventSender(String runId, String baseUrl) {
    this.runId = runId;
    this.enabled = !"off".equalsIgnoreCase(baseUrl);
    this.eventsUri = enabled ? URI.create(baseUrl + "/api/events") : null;
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    this.executor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "live-test-dashboard-sender");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Reads {@code -Ddashboard.url}, then {@code DASHBOARD_URL}; the value {@code off} disables. */
  static String configuredUrl() {
    String property = System.getProperty("dashboard.url");
    if (property != null && !property.isBlank()) {
      return property.trim();
    }
    String environment = System.getenv("DASHBOARD_URL");
    return environment != null && !environment.isBlank() ? environment.trim() : DEFAULT_URL;
  }

  void send(String json) {
    if (enabled) {
      executor.execute(() -> post(json));
    }
  }

  /** Waits for queued events, so the last ones are not lost when the test JVM exits. */
  void flush() {
    try {
      executor.submit(() -> {}).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      disable(e.toString());
    }
  }

  private void post(String json) {
    if (!enabled) {
      return;
    }
    HttpRequest request =
        HttpRequest.newBuilder(eventsUri)
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .header("X-Run-Id", runId)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    try {
      int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status >= 300) {
        disable("HTTP " + status);
      }
    } catch (IOException e) {
      disable(e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void disable(String reason) {
    enabled = false;
    System.err.println("[live-test-dashboard] not sending results to " + eventsUri + ": " + reason);
  }
}
