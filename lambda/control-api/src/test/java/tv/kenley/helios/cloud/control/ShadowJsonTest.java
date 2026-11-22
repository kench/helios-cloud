package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.core.serde.document.Document;

class ShadowJsonTest {
  private static Document doc(String json) {
    return ShadowJson.parse(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void walksMapsAndReadsTypedValues() {
    Document d =
        doc(
            "{\"a\":{\"s\":\"x\",\"i\":7,\"f\":2.5,\"big\":12345678901,\"b\":true,\"n\":null,"
                + "\"l\":[1,null,\"y\"],\"m\":{\"k\":1}}}");
    assertEquals(Optional.of("x"), ShadowJson.string(d, "a", "s"));
    assertEquals(Optional.empty(), ShadowJson.string(d, "a", "i"), "not a string");
    assertEquals(Optional.of(7), ShadowJson.integer(d, "a", "i"));
    assertEquals(Optional.empty(), ShadowJson.integer(d, "a", "f"), "not whole");
    assertEquals(Optional.empty(), ShadowJson.integer(d, "a", "big"), "out of int range");
    assertEquals(Optional.of(12345678901L), ShadowJson.whole(d, "a", "big"));
    assertEquals(2.5, ShadowJson.number(d, "a", "f").orElseThrow().doubleValue());
    assertEquals(Optional.of(true), ShadowJson.bool(d, "a", "b"));
    assertEquals(Optional.empty(), ShadowJson.bool(d, "a", "s"));
    assertEquals(Optional.empty(), ShadowJson.at(d, "a", "n"), "null is absent");
    assertTrue(ShadowJson.has(d, "a", "n"), "but the key is there");
    assertFalse(ShadowJson.has(d, "a", "missing"));
    assertFalse(ShadowJson.has(d, "a", "s", "deeper"));
    assertTrue(ShadowJson.has(d));
    assertEquals(2, ShadowJson.list(d, "a", "l").size(), "nulls dropped");
    assertEquals(List.of(), ShadowJson.list(d, "a", "m"));
    assertEquals(1, ShadowJson.map(d, "a", "m").size());
    assertEquals(Map.of(), ShadowJson.map(d, "a", "s"));
    assertEquals(Optional.empty(), ShadowJson.at(d, "a", "s", "deeper"));
    assertEquals(Optional.empty(), ShadowJson.at(null, "a"));
  }

  @Test
  void refusesWhatIsntJson() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ShadowJson.parse("{nope".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void writesMapsListsAndNulls() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("s", "a\"b");
    value.put("i", 1);
    value.put("l", 2L);
    value.put("b", false);
    value.put("n", ShadowJson.NULL);
    value.put("list", List.of(1, "x", Map.of("k", true)));
    assertEquals(
        "{\"s\":\"a\\\"b\",\"i\":1,\"l\":2,\"b\":false,\"n\":null,"
            + "\"list\":[1,\"x\",{\"k\":true}]}",
        ShadowJson.write(value));
    assertEquals("[]", new String(ShadowJson.bytes(List.of()), StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> ShadowJson.write(Map.of("x", 1.5)));
    List<Object> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThrows(IllegalArgumentException.class, () -> ShadowJson.write(withNull));
  }
}
