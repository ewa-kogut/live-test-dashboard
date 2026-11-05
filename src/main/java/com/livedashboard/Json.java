package com.livedashboard;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes the small JSON events the listener sends, so test projects get no extra dependency. */
final class Json {

  private Json() {}

  static String write(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(entry -> quote(String.valueOf(entry.getKey())) + ":" + write(entry.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream().map(Json::write).collect(Collectors.joining(",", "[", "]"));
    }
    return quote(value.toString());
  }

  private static String quote(String text) {
    StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
    text.chars()
        .forEach(
            character -> {
              switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                  if (character < 0x20) {
                    quoted.append(String.format("\\u%04x", character));
                  } else {
                    quoted.append((char) character);
                  }
                }
              }
            });
    return quoted.append('"').toString();
  }
}
