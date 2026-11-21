package tv.kenley.helios.cloud.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import tv.kenley.helios.cloud.common.ChallengeKeys;

/** The challenge message and signature, checked against helios-contract's challenge-v1.json. */
final class ChallengeSignatureTest {
  private static final ObjectNode VECTORS = vectors();
  private static final String KEY =
      VECTORS.expectObjectMember("testKey").expectStringMember("publicKeySpkiPem").getValue();
  private static final String CHALLENGE_ID =
      VECTORS.expectObjectMember("challenge").expectStringMember("challengeId").getValue();
  private static final byte[] SIGNATURE =
      Base64.getDecoder()
          .decode(
              VECTORS.expectObjectMember("signature").expectStringMember("derBase64").getValue());

  @TestFactory
  Stream<DynamicTest> everyVectorCase() {
    return VECTORS.expectArrayMember("cases").getElements().stream()
        .map(Node::expectObjectNode)
        .map(c -> DynamicTest.dynamicTest(c.expectStringMember("id").getValue(), () -> check(c)));
  }

  private static void check(ObjectNode c) throws NoSuchAlgorithmException {
    List<String> macs =
        c.expectArrayMember("macs").getElements().stream()
            .map(Node::expectStringNode)
            .map(StringNode::getValue)
            .toList();
    String nonce = c.expectStringMember("nonce").getValue();

    byte[] message = ChallengeSignature.message(CHALLENGE_ID, nonce, macs);

    assertArrayEquals(
        c.expectStringMember("message").getValue().getBytes(StandardCharsets.UTF_8), message);
    assertEquals(
        c.expectStringMember("messageSha256Hex").getValue(),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message)));
    assertEquals(
        c.expectBooleanMember("signatureVerifies").getValue(),
        ChallengeSignature.verify(KEY, message, SIGNATURE));
  }

  @Test
  void malformedSignatureDoesNotVerify() {
    byte[] message = ChallengeSignature.message(CHALLENGE_ID, "AA==", List.of("00:11:22:33:44:55"));

    assertFalse(ChallengeSignature.verify(KEY, message, new byte[] {1, 2, 3}));
  }

  @Test
  void anotherKeyDoesNotVerify() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    String other = pem(generator.generateKeyPair().getPublic().getEncoded());
    byte[] message =
        ChallengeSignature.message(
            CHALLENGE_ID,
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            List.of("dc:a6:32:00:00:09", "2c:cf:67:00:00:02"));

    assertTrue(ChallengeSignature.verify(KEY, message, SIGNATURE));
    assertFalse(ChallengeSignature.verify(other, message, SIGNATURE));
  }

  @Test
  void rejectsKeysThatAreNotP256() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp384r1"));
    String p384 = pem(generator.generateKeyPair().getPublic().getEncoded());
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String rsaKey = pem(rsa.generateKeyPair().getPublic().getEncoded());

    assertThrows(IllegalArgumentException.class, () -> ChallengeKeys.parseP256(p384));
    assertThrows(IllegalArgumentException.class, () -> ChallengeKeys.parseP256(rsaKey));
    assertThrows(
        IllegalArgumentException.class, () -> ChallengeKeys.parseP256(pem(new byte[] {1})));
  }

  @Test
  void sortsTheMacs() {
    assertArrayEquals(
        ChallengeSignature.message("c-1", "AA==", List.of("b", "a")),
        ChallengeSignature.message("c-1", "AA==", List.of("a", "b")));
  }

  private static String pem(byte[] der) {
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
        + "\n-----END PUBLIC KEY-----\n";
  }

  private static ObjectNode vectors() {
    try {
      return Node.parse(Files.readString(Path.of(System.getProperty("helios.contract.vectors"))))
          .expectObjectNode();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
