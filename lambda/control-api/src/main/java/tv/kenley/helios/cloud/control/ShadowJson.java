package tv.kenley.helios.cloud.control;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import software.amazon.smithy.java.json.JsonCodec;
import software.amazon.smithy.model.shapes.ShapeType;
import tv.kenley.helios.cloud.common.Json;

/**
 * JSON for device shadows: reading a shadow document as a Smithy {@link Document} (null-safe walks
 * over maps), and writing the small JSON the Control API sends to {@code UpdateThingShadow}.
 */
final class ShadowJson {
  private static final JsonCodec CODEC = JsonCodec.builder().build();

  /** A JSON {@code null} in a document written by {@link #write}. */
  static final Object NULL = new Object();

  private ShadowJson() {}

  /**
   * Parses {@code json}.
   *
   * @throws IllegalArgumentException if it isn't JSON
   */
  static Document parse(byte[] json) {
    try {
      return CODEC.createDeserializer(json).readDocument();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("not JSON", e);
    }
  }

  /** The member at {@code path} (map keys), if every step is a map that has it and isn't null. */
  static Optional<Document> at(Document document, String... path) {
    Document current = document;
    for (String key : path) {
      if (current == null || !isMap(current)) {
        return Optional.empty();
      }
      current = current.asStringMap().get(key);
    }
    return Optional.ofNullable(current);
  }

  /** Whether the map at {@code path} minus its last key has that key, even with a null value. */
  static boolean has(Document document, String... path) {
    if (path.length == 0) {
      return document != null;
    }
    String[] parent = Arrays.copyOf(path, path.length - 1);
    return at(document, parent)
        .filter(ShadowJson::isMap)
        .map(map -> map.asStringMap().containsKey(path[path.length - 1]))
        .orElse(false);
  }

  /** The string at {@code path}, if it is one. */
  static Optional<String> string(Document document, String... path) {
    return at(document, path).filter(ShadowJson::isString).map(Document::asString);
  }

  /** The number at {@code path} as an int, if it is a whole number in range. */
  static Optional<Integer> integer(Document document, String... path) {
    return number(document, path)
        .filter(n -> n.doubleValue() == Math.rint(n.doubleValue()))
        .filter(n -> n.doubleValue() >= Integer.MIN_VALUE && n.doubleValue() <= Integer.MAX_VALUE)
        .map(Number::intValue);
  }

  /** The number at {@code path} as a long, if it is a whole number. */
  static Optional<Long> whole(Document document, String... path) {
    return number(document, path)
        .filter(n -> n.doubleValue() == Math.rint(n.doubleValue()))
        .map(Number::longValue);
  }

  /** The number at {@code path}, if it is one. */
  static Optional<Number> number(Document document, String... path) {
    return at(document, path).filter(ShadowJson::isNumber).map(Document::asNumber);
  }

  /** The boolean at {@code path}, if it is one. */
  static Optional<Boolean> bool(Document document, String... path) {
    return at(document, path).filter(d -> d.type() == ShapeType.BOOLEAN).map(Document::asBoolean);
  }

  /** The map at {@code path}, empty if there's none. */
  static Map<String, Document> map(Document document, String... path) {
    return at(document, path).filter(ShadowJson::isMap).map(Document::asStringMap).orElse(Map.of());
  }

  /** The list at {@code path}, empty if there's none; null entries are dropped. */
  static List<Document> list(Document document, String... path) {
    return at(document, path)
        .filter(d -> d.type() == ShapeType.LIST)
        .map(d -> d.asList().stream().filter(Objects::nonNull).toList())
        .orElse(List.of());
  }

  /** The strings in the list at {@code path}, in order; anything else in it is skipped. */
  static List<String> strings(Document document, String... path) {
    return list(document, path).stream()
        .filter(ShadowJson::isString)
        .map(Document::asString)
        .toList();
  }

  static boolean isMap(Document document) {
    return switch (document.type()) {
      case MAP, STRUCTURE, UNION -> true;
      default -> false;
    };
  }

  private static boolean isString(Document document) {
    return document.type() == ShapeType.STRING;
  }

  private static boolean isNumber(Document document) {
    return switch (document.type()) {
      case BYTE, SHORT, INTEGER, INT_ENUM, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL -> true;
      default -> false;
    };
  }

  /** {@link #write} as UTF-8 bytes. */
  static byte[] bytes(Object value) {
    return write(value).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * {@code value} as JSON: a {@code Map<String, ?>} (in its iteration order), a {@code List<?>}, a
   * {@code String}, a {@code Number}, a {@code Boolean} or {@link #NULL}.
   *
   * @throws IllegalArgumentException for anything else
   */
  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    switch (value) {
      case null -> throw new IllegalArgumentException("use ShadowJson.NULL for a JSON null");
      case String s -> out.append(Json.quote(s));
      case Boolean b -> out.append(b);
      case Integer i -> out.append(i);
      case Long l -> out.append(l);
      case Map<?, ?> m -> {
        List<String> members = new ArrayList<>();
        for (Map.Entry<?, ?> entry : m.entrySet()) {
          StringBuilder member = new StringBuilder(Json.quote((String) entry.getKey())).append(':');
          write(entry.getValue(), member);
          members.add(member.toString());
        }
        out.append('{').append(String.join(",", members)).append('}');
      }
      case List<?> l -> {
        List<String> items = new ArrayList<>();
        for (Object item : l) {
          StringBuilder element = new StringBuilder();
          write(item, element);
          items.add(element.toString());
        }
        out.append('[').append(String.join(",", items)).append(']');
      }
      default -> {
        if (value == NULL) {
          out.append("null");
        } else {
          throw new IllegalArgumentException("can't write " + value.getClass().getName());
        }
      }
    }
  }
}
