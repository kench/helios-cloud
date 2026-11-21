package tv.kenley.helios.cloud.common;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULIDs: 48 bits of milliseconds and 80 random bits in Crockford base32, 26 characters. IDs are a
 * short prefix and a ULID, such as {@code c-01JD5W3Q8T6M2K4X9B0N7C1D2E} (Bootstrap API, section 5).
 */
public final class Ulid {
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final int LENGTH = 26;
  private static final int RANDOM_BYTES = 10;

  private Ulid() {}

  /** A new ID: {@code prefix}, then a ULID for {@code time}. */
  public static String generate(String prefix, Instant time, SecureRandom random) {
    byte[] entropy = new byte[RANDOM_BYTES];
    random.nextBytes(entropy);
    // 128 bits: the time in the top 48, then the randomness.
    long high = (time.toEpochMilli() << 16) | ((entropy[0] & 0xffL) << 8) | (entropy[1] & 0xffL);
    long low = 0;
    for (int i = 2; i < RANDOM_BYTES; i++) {
      low = (low << 8) | (entropy[i] & 0xffL);
    }
    char[] out = new char[LENGTH];
    for (int i = LENGTH - 1; i >= 0; i--) {
      out[i] = ALPHABET[(int) (low & 31)];
      // Shift the 128-bit value right by 5.
      low = (low >>> 5) | ((high & 31) << 59);
      high >>>= 5;
    }
    return prefix + new String(out);
  }
}
