package tv.kenley.helios.cloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class JsonTest {
  @Test
  void escapesStrings() {
    assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001\"", Json.quote("a\"b\\c\nd\re\tf\u0001"));
  }

  @Test
  void writesFieldsAndArrays() {
    assertEquals("\"a\":\"b\"", Json.field("a", "b"));
    assertEquals("[\"x\",\"y\"]", Json.array(List.of("x", "y")));
    assertEquals("[]", Json.array(List.of()));
  }
}
