package tv.kenley.helios.cloud.device;

import java.util.List;
import java.util.stream.Collectors;

/** Just enough JSON writing for log lines (RFC 8259 string escaping). */
final class Json {
  private Json() {}

  /** {@code value} as a JSON string. */
  static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /** {@code "name":"value"}. */
  static String field(String name, String value) {
    return quote(name) + ":" + quote(value);
  }

  /** {@code values} as a JSON array of strings. */
  static String array(List<String> values) {
    return values.stream().map(Json::quote).collect(Collectors.joining(",", "[", "]"));
  }
}
