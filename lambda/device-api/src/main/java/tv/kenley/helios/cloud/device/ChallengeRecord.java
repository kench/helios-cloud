package tv.kenley.helios.cloud.device;

import java.time.Instant;
import java.util.List;

/**
 * An issued challenge (Bootstrap API, sections 3.2 and 5): bound to one device record and to the
 * MACs of the request it answered, single-use, and valid until {@code expiresAt}.
 *
 * @param challengeId {@code c-} and a ULID
 * @param deviceId the device record it was issued for
 * @param macs the request's MACs, sorted ascending
 * @param nonceBase64 the nonce exactly as sent to the device
 * @param expiresAt when it stops being valid
 */
record ChallengeRecord(
    String challengeId, String deviceId, List<String> macs, String nonceBase64, Instant expiresAt) {

  ChallengeRecord {
    macs = macs.stream().sorted().toList();
  }
}
