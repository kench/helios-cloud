package tv.kenley.helios.cloud.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import tv.kenley.helios.cloud.common.RestJson;
import tv.kenley.helios.cloud.model.AnswerChallengeInput;
import tv.kenley.helios.cloud.model.BootstrapInput;
import tv.kenley.helios.cloud.model.BootstrapResult;
import tv.kenley.helios.cloud.model.Challenge;
import tv.kenley.helios.cloud.model.DeviceDisabled;
import tv.kenley.helios.cloud.model.DeviceIdentity;
import tv.kenley.helios.cloud.model.SignatureAlgorithm;
import tv.kenley.helios.cloud.model.TokenLabels;

@ExtendWith(MockitoExtension.class)
final class DeviceApiTest {
  private static final String PIN = "839275103846";

  /** The contract's minimal Bootstrap request (protocol test BootstrapRequestMinimal). */
  private static final String MINIMAL =
      """
      {
          "adapters": [
              {"interfaceName": "eth0", "kind": "ethernet", "mac": "00:11:22:33:44:55"}
          ],
          "heliosVersion": "0.1.0"
      }\
      """;

  private static final DeviceIdentity IDENTITY =
      DeviceIdentity.builder()
          .deviceId("d-1")
          .thingName("t-1")
          .pin(PIN)
          .token(TokenLabels.builder().tokenLabel("helios").keyLabel("device-key").build())
          .build();

  @Mock private BootstrapService service;

  private final List<String> audit = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private DeviceApi api;

  @BeforeEach
  void setUp() {
    api =
        new DeviceApi(
            service,
            audit::add,
            errors::add,
            Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneOffset.UTC));
  }

  private RestJson.Response post(String path, String body) {
    return api.handle(
        new RestJson.Request(
            "POST", path, body.getBytes(StandardCharsets.UTF_8), "203.0.113.7", "request-1", null));
  }

  private static ObjectNode json(String body) {
    return Node.parse(body).expectObjectNode();
  }

  private static void assertCommonHeaders(RestJson.Response response) {
    assertEquals("application/json", response.headers().get("Content-Type"));
    assertEquals("no-store", response.headers().get("Cache-Control"));
  }

  @Test
  void bootstrapReturnsTheIdentityAsThePayload() {
    ArgumentCaptor<BootstrapInput> input = ArgumentCaptor.forClass(BootstrapInput.class);
    when(service.bootstrap(input.capture(), any()))
        .thenReturn(BootstrapResult.builder().identity(IDENTITY).build());

    RestJson.Response response = post(DeviceApi.BOOTSTRAP_PATH, MINIMAL);

    assertEquals(200, response.status());
    assertCommonHeaders(response);
    assertFalse(response.headers().containsKey("X-Amzn-Errortype"));
    ObjectNode identity = json(response.body()).expectObjectMember("identity");
    assertEquals(PIN, identity.expectStringMember("pin").getValue());
    assertEquals("00:11:22:33:44:55", input.getValue().getAdapters().getFirst().getMac());
    assertEquals(1, audit.size());
    ObjectNode entry = json(audit.getFirst());
    assertEquals("Bootstrap", entry.expectStringMember("operation").getValue());
    assertEquals("identity", entry.expectStringMember("result").getValue());
    assertEquals("203.0.113.7", entry.expectStringMember("sourceIp").getValue());
    assertEquals("request-1", entry.expectStringMember("requestId").getValue());
  }

  @Test
  void thePinNeverReachesTheLogs() {
    when(service.bootstrap(any(), any()))
        .thenReturn(BootstrapResult.builder().identity(IDENTITY).build());

    post(DeviceApi.BOOTSTRAP_PATH, MINIMAL);

    for (String line : audit) {
      assertFalse(line.contains(PIN), line);
    }
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void bootstrapCanReturnChallenge() {
    when(service.bootstrap(any(), any()))
        .thenReturn(
            BootstrapResult.builder()
                .challenge(
                    Challenge.builder()
                        .challengeId("c-1")
                        .nonce(ByteBuffer.wrap(new byte[] {0, 1, 2, 3}))
                        .alg(SignatureAlgorithm.ECDSA_P256_SHA256)
                        .expiresAt(Instant.parse("2026-11-21T03:01:00Z"))
                        .build())
                .build());

    RestJson.Response response = post(DeviceApi.BOOTSTRAP_PATH, MINIMAL);

    ObjectNode challenge = json(response.body()).expectObjectMember("challenge");
    assertEquals("AAECAw==", challenge.expectStringMember("nonce").getValue());
    assertEquals("2026-11-21T03:01:00Z", challenge.expectStringMember("expiresAt").getValue());
    assertEquals("ecdsa-p256-sha256", challenge.expectStringMember("alg").getValue());
    assertEquals("challenge", json(audit.getFirst()).expectStringMember("result").getValue());
  }

  @Test
  void answerChallengeReturnsTheIdentityUnwrapped() {
    ArgumentCaptor<AnswerChallengeInput> input =
        ArgumentCaptor.forClass(AnswerChallengeInput.class);
    when(service.answerChallenge(input.capture(), any())).thenReturn(IDENTITY);

    RestJson.Response response =
        post(
            DeviceApi.CHALLENGE_PATH,
            """
            {"challengeId": "c-01JD5W3Q8T6M2K4X9B0N7C1D2E", "signature": "MEQCIB2koQlq",
             "adapters": [{"interfaceName": "eth0", "kind": "ethernet",
                           "mac": "00:11:22:33:44:55"}]}\
            """);

    assertEquals(200, response.status());
    assertEquals("d-1", json(response.body()).expectStringMember("deviceId").getValue());
    assertEquals(9, input.getValue().getSignature().remaining());
    assertEquals(
        "AnswerChallenge", json(audit.getFirst()).expectStringMember("operation").getValue());
  }

  @Test
  void modelledErrorsUseRestJson1() {
    when(service.bootstrap(any(), any()))
        .thenThrow(DeviceDisabled.builder().message("Device is disabled.").build());

    RestJson.Response response = post(DeviceApi.BOOTSTRAP_PATH, MINIMAL);

    assertEquals(403, response.status());
    assertCommonHeaders(response);
    assertEquals("DeviceDisabled", response.headers().get("X-Amzn-Errortype"));
    assertEquals(
        "Device is disabled.", json(response.body()).expectStringMember("message").getValue());
    assertEquals("DeviceDisabled", json(audit.getFirst()).expectStringMember("result").getValue());
  }

  @Test
  void malformedJsonIsValidationError() {
    RestJson.Response response = post(DeviceApi.BOOTSTRAP_PATH, "{not json");

    assertEquals(400, response.status());
    assertEquals("ValidationException", response.headers().get("X-Amzn-Errortype"));
    verifyNoInteractions(service);
    assertEquals(
        "ValidationException", json(audit.getFirst()).expectStringMember("result").getValue());
  }

  @Test
  void constraintsAreCheckedAgainstTheModel() {
    RestJson.Response response =
        post(
            DeviceApi.BOOTSTRAP_PATH,
            """
            {"adapters": [{"interfaceName": "eth0", "kind": "ethernet", "mac": "not-a-mac"}],
             "heliosVersion": "0.1.0"}\
            """);

    assertEquals(400, response.status());
    ObjectNode body = json(response.body());
    String path =
        body.expectArrayMember("fieldList")
            .getElements()
            .getFirst()
            .expectObjectNode()
            .expectStringMember("path")
            .getValue();
    assertTrue(path.contains("mac"), path);
    verify(service, never()).bootstrap(any(), any());
  }

  @Test
  void missingRequiredMembersAreValidationErrors() {
    assertEquals(400, post(DeviceApi.BOOTSTRAP_PATH, "{\"heliosVersion\": \"0.1.0\"}").status());
    assertEquals(400, post(DeviceApi.BOOTSTRAP_PATH, "").status());
    verifyNoInteractions(service);
  }

  @Test
  void unknownRoutesAreNotFound() {
    RestJson.Response response =
        api.handle(
            new RestJson.Request(
                "GET", DeviceApi.BOOTSTRAP_PATH, new byte[0], "203.0.113.7", "request-1", null));

    assertEquals(404, response.status());
    assertCommonHeaders(response);
    assertEquals("UnknownOperationException", response.headers().get("X-Amzn-Errortype"));
    assertEquals(404, post("/v1/admin/devices", "{}").status());
    assertTrue(audit.isEmpty());
  }

  @Test
  void unexpectedFailuresAreInternalErrors() {
    when(service.bootstrap(any(), any())).thenThrow(new IllegalStateException("DynamoDB down"));

    RestJson.Response response = post(DeviceApi.BOOTSTRAP_PATH, MINIMAL);

    assertEquals(500, response.status());
    assertEquals("InternalError", response.headers().get("X-Amzn-Errortype"));
    assertEquals("Internal error.", json(response.body()).expectStringMember("message").getValue());
    assertTrue(errors.getFirst().contains("DynamoDB down"), errors.toString());
    assertEquals("InternalError", json(audit.getFirst()).expectStringMember("result").getValue());
  }
}
