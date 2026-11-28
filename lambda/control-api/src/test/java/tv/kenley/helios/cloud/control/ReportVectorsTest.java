package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import tv.kenley.helios.cloud.common.RestJson;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;

/**
 * The contract's report vectors (helios-contract {@code test-vectors/control-reports-v1.json}): for
 * each case, the device's reported shadows go in, and the Control API must answer the read with the
 * case's status, error type and (for a 200) body. Helios's on-device console answers the same cases
 * (LLD 13), so the two can't drift apart.
 */
class ReportVectorsTest {
  static final String ID = DeviceDirectoryTest.ID;
  static final String THING = DeviceDirectoryTest.THING;

  static List<ObjectNode> cases() throws Exception {
    String path = System.getProperty("helios.contract.reportVectors");
    return Node.parse(Files.readString(Path.of(path)))
        .expectObjectNode()
        .expectArrayMember("cases")
        .getElementsAs(ObjectNode.class);
  }

  static Stream<Arguments> eachCase() throws Exception {
    return cases().stream()
        .map(vector -> Arguments.of(vector.expectStringMember("name").getValue(), vector));
  }

  @Test
  void hasCases() throws Exception {
    assertFalse(cases().isEmpty(), "the contract's report vectors are missing");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("eachCase")
  void answersAsTheVectorSays(String name, ObjectNode vector) {
    ControlServiceTest.FakeShadows shadows = new ControlServiceTest.FakeShadows();
    vector
        .expectObjectMember("reported")
        .getStringMap()
        .forEach((shadow, reported) ->
            shadows.documents.put(
                THING + "/" + shadow,
                Node.printJson(
                        Node.objectNode()
                            .withMember(
                                "state", Node.objectNode().withMember("reported", reported)))
                    .getBytes(StandardCharsets.UTF_8)));

    ObjectNode request = vector.expectObjectMember("request");
    String path = request.expectStringMember("path").getValue().replace("{deviceId}", ID);
    for (Map.Entry<String, Node> param :
        request
            .getObjectMember("params")
            .map(ObjectNode::getStringMap)
            .orElse(Map.of())
            .entrySet()) {
      path =
          path.replace("{" + param.getKey() + "}", param.getValue().expectStringNode().getValue());
    }
    RestJson.Response response =
        api(shadows)
            .handle(
                new RestJson.Request(
                    request.expectStringMember("method").getValue(),
                    path,
                    new byte[0],
                    "198.51.100.7",
                    "req-1",
                    "arn:aws:sts::123456789012:assumed-role/helios-operator/test"),
                Map.of());

    ObjectNode expected = vector.expectObjectMember("response");
    assertEquals(
        expected.expectNumberMember("status").getValue().intValue(), response.status(), name);
    String type = response.headers().get("X-Amzn-Errortype");
    assertEquals(
        expected.getStringMember("errorType").map(s -> s.getValue()).orElse(null),
        type == null ? null : type.split(":")[0],
        name);
    if (expected.getMember("body").isPresent() && response.status() == 200) {
      Node body = Node.parse(response.body());
      assertTrue(
          expected.expectMember("body").equals(body),
          () ->
              name
                  + ": expected "
                  + Node.prettyPrintJson(expected.expectMember("body"))
                  + "\nbut was "
                  + Node.prettyPrintJson(body));
    }
  }

  private static ControlApi api(ControlServiceTest.FakeShadows shadows) {
    DeviceDirectory devices = mock(DeviceDirectory.class);
    when(devices.get(ID))
        .thenReturn(
            Optional.of(
                DeviceSummary.builder()
                    .deviceId(ID)
                    .thingName(THING)
                    .role(DeviceRole.TRANSCODER)
                    .status(DeviceStatus.ACTIVE)
                    .build()));
    WifiControlTest.FakeCommands commands = new WifiControlTest.FakeCommands();
    return new ControlApi(
        ControlServiceTest.service(
            devices, shadows, commands, new StreamCommandsTest.MemorySettings()),
        new WifiControl(devices, shadows, commands, Clock.systemUTC(), duration -> {}),
        line -> {},
        line -> {},
        Clock.fixed(Instant.parse("2026-11-22T21:00:00Z"), ZoneOffset.UTC));
  }
}
