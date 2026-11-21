package tv.kenley.helios.cloud.device;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.List;
import tv.kenley.helios.cloud.common.ChallengeKeys;

/**
 * The bootstrap challenge signature (Bootstrap API, section 3.2): the signed message, and ECDSA
 * P-256 with SHA-256 verification of a DER signature against the device's registered challenge key.
 */
final class ChallengeSignature {
  private static final String PREFIX = "helios-bootstrap-v1";

  private ChallengeSignature() {}

  /**
   * The bytes Helios signs: the prefix, challenge ID, nonce (base64, exactly as sent) and the
   * request's MACs sorted ascending and joined with commas, separated by newlines, with no trailing
   * newline.
   */
  static byte[] message(String challengeId, String nonceBase64, List<String> macs) {
    String joined = String.join(",", macs.stream().sorted().toList());
    return String.join("\n", PREFIX, challengeId, nonceBase64, joined)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Whether {@code derSignature} is a valid signature of {@code message} by the P-256 key in {@code
   * publicKeyPem} (a PEM SubjectPublicKeyInfo). A malformed signature doesn't verify.
   *
   * @throws IllegalArgumentException if the key isn't a P-256 public key in PEM
   */
  static boolean verify(String publicKeyPem, byte[] message, byte[] derSignature) {
    PublicKey key = ChallengeKeys.parseP256(publicKeyPem);
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(key);
      verifier.update(message);
      return verifier.verify(derSignature);
    } catch (GeneralSecurityException e) {
      // A signature that isn't valid DER, for example.
      return false;
    }
  }
}
