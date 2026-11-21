package tv.kenley.helios.cloud.device;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * The bootstrap challenge signature (Bootstrap API, section 3.2): the signed message, and ECDSA
 * P-256 with SHA-256 verification of a DER signature against the device's registered challenge key.
 */
final class ChallengeSignature {
  private static final String PREFIX = "helios-bootstrap-v1";
  private static final BigInteger P256_ORDER = p256Order();

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
    PublicKey key = parseP256(publicKeyPem);
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

  /**
   * The P-256 public key in {@code pem}.
   *
   * @throws IllegalArgumentException if it isn't one
   */
  static ECPublicKey parseP256(String pem) {
    String base64 =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
      if (key instanceof ECPublicKey ec && ec.getParams().getOrder().equals(P256_ORDER)) {
        return ec;
      }
      throw new IllegalArgumentException("not a P-256 public key");
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not an EC public key in PEM", e);
    }
  }

  private static BigInteger p256Order() {
    try {
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class).getOrder();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the JDK has no P-256", e);
    }
  }
}
