package com.livedashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/** JUnit finds this listener on the classpath and reports the run to the dashboard server. */
public class DashboardListener implements TestExecutionListener {

  /**
   * One per JVM, because Surefire reruns start another test plan that belongs to the same run. With
   * {@code -Ddashboard.run.id}, several JVMs can share a run, such as the stages of a CI build.
   */
  private static final String RUN_ID =
      System.getProperty("dashboard.run.id", UUID.randomUUID().toString());

  private static final int MAX_MESSAGE_LENGTH = 4_000;

  private final EventSender sender = new EventSender(RUN_ID, EventSender.configuredUrl());
  private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
  private final Set<String> finishedInThisPlan = ConcurrentHashMap.newKeySet();
  private volatile TestPlan testPlan;

  @Override
  public void testPlanExecutionStarted(TestPlan plan) {
    testPlan = plan;
    finishedInThisPlan.clear();
    List<Object> tests = new ArrayList<>();
    for (TestIdentifier root : plan.getRoots()) {
      plan.getDescendants(root).stream()
          .filter(TestIdentifier::isTest)
          .forEach(test -> tests.add(describe(test)));
    }
    Map<String, Object> event = event("run-started");
    event.put("tests", tests);
    sender.send(Json.write(event));
  }

  @Override
  public void testPlanExecutionFinished(TestPlan plan) {
    sender.send(Json.write(event("run-finished")));
    sender.flush();
  }

  @Override
  public void dynamicTestRegistered(TestIdentifier identifier) {
    if (identifier.isTest()) {
      Map<String, Object> event = event("test-added");
      event.put("test", describe(identifier));
      sender.send(Json.write(event));
    }
  }

  @Override
  public void executionStarted(TestIdentifier identifier) {
    if (identifier.isTest()) {
      startTimes.put(identifier.getUniqueId(), System.nanoTime());
      Map<String, Object> event = event("test-started");
      event.put("id", identifier.getUniqueId());
      sender.send(Json.write(event));
    }
  }

  @Override
  public void executionSkipped(TestIdentifier identifier, String reason) {
    if (identifier.isTest()) {
      sendFinished(identifier, "skipped", reason);
    } else {
      testPlan.getDescendants(identifier).stream()
          .filter(TestIdentifier::isTest)
          .forEach(test -> sendFinished(test, "skipped", reason));
    }
  }

  @Override
  public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
    String message = result.getThrowable().map(DashboardListener::shorten).orElse(null);
    if (identifier.isTest()) {
      sendFinished(identifier, status(result), message);
    } else if (result.getStatus() == TestExecutionResult.Status.FAILED) {
      // A failure in e.g. @BeforeAll means the class's tests never ran: without this they would
      // stay queued for ever.
      testPlan.getDescendants(identifier).stream()
          .filter(TestIdentifier::isTest)
          .filter(test -> !finishedInThisPlan.contains(test.getUniqueId()))
          .forEach(test -> sendFinished(test, "failed", message));
    }
  }

  private void sendFinished(TestIdentifier test, String status, String message) {
    finishedInThisPlan.add(test.getUniqueId());
    Long startTime = startTimes.remove(test.getUniqueId());
    Map<String, Object> event = event("test-finished");
    event.put("id", test.getUniqueId());
    event.put("status", status);
    event.put("durationMs", startTime == null ? 0 : (System.nanoTime() - startTime) / 1_000_000);
    event.put("message", message);
    sender.send(Json.write(event));
  }

  private static String status(TestExecutionResult result) {
    return switch (result.getStatus()) {
      case SUCCESSFUL -> "passed";
      case FAILED -> "failed";
      case ABORTED -> "skipped";
    };
  }

  private Map<String, Object> describe(TestIdentifier test) {
    Map<String, Object> description = new LinkedHashMap<>();
    description.put("id", test.getUniqueId());
    description.put("name", displayName(test));
    description.put("group", className(test));
    description.put(
        "tag", test.getTags().stream().map(TestTag::getName).sorted().findFirst().orElse(null));
    return description;
  }

  /** Puts the test's name before a variant's name, e.g. "Login fails › wrong password". */
  private String displayName(TestIdentifier test) {
    return testPlan
        .getParent(test)
        .filter(parent -> parent.getSource().filter(MethodSource.class::isInstance).isPresent())
        .map(parent -> parent.getDisplayName() + " › " + test.getDisplayName())
        .orElse(test.getDisplayName());
  }

  private String className(TestIdentifier test) {
    Optional<TestIdentifier> current = Optional.of(test);
    while (current.isPresent()) {
      Optional<TestSource> source = current.get().getSource();
      if (source.isPresent() && source.get() instanceof ClassSource classSource) {
        return simpleName(classSource.getClassName());
      }
      if (source.isPresent() && source.get() instanceof MethodSource methodSource) {
        return simpleName(methodSource.getClassName());
      }
      current = testPlan.getParent(current.get());
    }
    return "";
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private static String shorten(Throwable throwable) {
    String text = throwable.toString();
    return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "…";
  }

  private static Map<String, Object> event(String type) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", type);
    event.put("at", System.currentTimeMillis());
    return event;
  }
}
