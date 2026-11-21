package tv.kenley.helios.cloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class UlidTest {
  private static final SecureRandom RANDOM = new SecureRandom();

  @Test
  void isPrefixAnd26CrockfordCharacters() {
    String id = Ulid.generate("c-", Instant.now(), RANDOM);

    assertTrue(id.matches("c-[0-9A-HJKMNP-TV-Z]{26}"), id);
  }

  @Test
  void encodesTheTimeInTheFirstTenCharacters() {
    // From the ULID spec: 1469918176385 ms is 01ARYZ6S41.
    String id = Ulid.generate("", Instant.ofEpochMilli(1469918176385L), RANDOM);

    assertEquals("01ARYZ6S41", id.substring(0, 10));
  }

  @Test
  void sortsByTime() {
    String earlier = Ulid.generate("d-", Instant.ofEpochMilli(1_000), RANDOM);
    String later = Ulid.generate("d-", Instant.ofEpochMilli(2_000), RANDOM);

    assertTrue(earlier.compareTo(later) < 0);
  }

  @Test
  void differsEachTime() {
    Instant now = Instant.now();

    assertNotEquals(Ulid.generate("c-", now, RANDOM), Ulid.generate("c-", now, RANDOM));
  }
}
