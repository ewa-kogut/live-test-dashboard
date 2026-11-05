package com.livedashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonTests {

  @Test
  @DisplayName("Writes objects, lists, numbers, booleans and null")
  void shouldWriteAllValueTypes() {
    // Arrange
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("text", "a");
    value.put("number", 12);
    value.put("flag", true);
    value.put("nothing", null);
    value.put("list", List.of(1, "b"));

    // Act
    String json = Json.write(value);

    // Assert
    assertEquals(
        "{\"text\":\"a\",\"number\":12,\"flag\":true,\"nothing\":null,\"list\":[1,\"b\"]}", json);
  }

  @Test
  @DisplayName("Escapes characters that would break a JSON string")
  void shouldEscapeSpecialCharacters() {
    // Arrange
    String text = "quote \" slash \\ line\nreturn\r tab\t control\u0001";

    // Act
    String json = Json.write(text);

    // Assert
    assertEquals("\"quote \\\" slash \\\\ line\\nreturn\\r tab\\t control\\u0001\"", json);
  }
}
