package tv.kenley.helios.cloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

final class ChallengeKeysTest {
  /** The contract's test key (helios-contract test-vectors/challenge-v1.json). */
  private static final String TEST_KEY =
      """
      -----BEGIN PUBLIC KEY-----
      MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENdP2+fbf8y3QR9YHaBi6IsqvjMAB
      lbVjADxzQded+2N4BMzgyJz9zx63fP59PfuPwPjR9TlfRbXkodWnfmPRYw==
      -----END PUBLIC KEY-----
      """;

  static String pem(PublicKey key) {
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(key.getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
  }

  @Test
  void fingerprintsAsTheContractDoes() {
    assertEquals(
        "073ab15dc70fb8a31b70f972294253b7922b4e5fbd8caa296ef7f6245a8c2e67",
        ChallengeKeys.fingerprint(ChallengeKeys.parseP256(TEST_KEY)));
  }

  @Test
  void rejectsKeysThatAreNotP256() throws Exception {
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp384r1"));
    String p384 = pem(ec.generateKeyPair().getPublic());
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String rsaKey = pem(rsa.generateKeyPair().getPublic());

    assertThrows(IllegalArgumentException.class, () -> ChallengeKeys.parseP256(p384));
    assertThrows(IllegalArgumentException.class, () -> ChallengeKeys.parseP256(rsaKey));
    assertThrows(IllegalArgumentException.class, () -> ChallengeKeys.parseP256("not a key"));
  }
}
