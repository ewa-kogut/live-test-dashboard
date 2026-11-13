package com.livedashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DashboardListenerTests {

  private static final String THIS_CLASS = DashboardListenerTests.class.getName();

  @Test
  @DisplayName("Points to the line in the test class where the failure happened")
  void shouldFindLineInTestClass() {
    // Arrange
    Throwable failure = new AssertionError("expected true");
    int line = failure.getStackTrace()[0].getLineNumber();

    // Act
    String location = DashboardListener.location(failure, THIS_CLASS);

    // Assert
    assertEquals(
        "DashboardListenerTests.shouldFindLineInTestClass(DashboardListenerTests.java:"
            + line
            + ")",
        location);
  }

  @Test
  @DisplayName("Finds the test line in a cause when the outer exception has none")
  void shouldFindLineInCause() {
    // Arrange – the outer exception's stack trace has no frame from the test class
    Throwable cause = new IllegalStateException("inner");
    Throwable outer = new RuntimeException("outer", cause);
    outer.setStackTrace(new StackTraceElement[0]);

    // Act
    String location = DashboardListener.location(outer, THIS_CLASS);

    // Assert
    assertEquals(
        "DashboardListenerTests.shouldFindLineInCause(DashboardListenerTests.java:"
            + cause.getStackTrace()[0].getLineNumber()
            + ")",
        location);
  }

  @Test
  @DisplayName("Gives no location when no frame belongs to the test class")
  void shouldReturnNullForOtherClass() {
    // Arrange
    Throwable failure = new AssertionError("expected true");

    // Act
    String location = DashboardListener.location(failure, "com.example.OtherTests");

    // Assert
    assertNull(location);
  }
}
