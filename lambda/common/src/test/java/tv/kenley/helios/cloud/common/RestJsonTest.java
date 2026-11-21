package tv.kenley.helios.cloud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.helios.cloud.model.DeviceDisabled;
import tv.kenley.helios.cloud.model.DeviceStatus;
import tv.kenley.helios.cloud.model.SetDeviceStatusInput;

final class RestJsonTest {
  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void decodesTheBodyAndAppliesPathLabels() {
    SetDeviceStatusInput input =
        RestJson.decode(
            bytes("{\"status\": \"disabled\", \"reason\": \"lost\"}"),
            SetDeviceStatusInput.builder(),
            b -> b.deviceId("d-01JD5VZ6R1H8P3S0W2Y4A6C8E0"));

    assertEquals("d-01JD5VZ6R1H8P3S0W2Y4A6C8E0", input.getDeviceId());
    assertEquals(DeviceStatus.DISABLED, input.getStatus());
    assertEquals("lost", input.getReason());
  }

  @Test
  void checksConstraintsIncludingPathLabels() {
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                RestJson.decode(
                    bytes("{\"status\": \"active\"}"),
                    SetDeviceStatusInput.builder(),
                    b -> b.deviceId("not-an-id")));

    assertEquals("/deviceId", e.getFieldList().getFirst().getPath());
  }

  @Test
  void malformedAndIncompleteBodiesAreValidationErrors() {
    assertThrows(
        ValidationException.class,
        () -> RestJson.decode(bytes("{nope"), SetDeviceStatusInput.builder()));
    // deviceId and status are required.
    assertThrows(
        ValidationException.class,
        () -> RestJson.decode(new byte[0], SetDeviceStatusInput.builder()));
  }

  @Test
  void modelledErrorsCarryTheirStatusAndType() {
    RestJson.Response response =
        RestJson.error(DeviceDisabled.builder().message("Device is disabled.").build());

    assertEquals(403, response.status());
    assertEquals("DeviceDisabled", response.headers().get("X-Amzn-Errortype"));
    assertEquals("no-store", response.headers().get("Cache-Control"));
    assertEquals("{\"message\":\"Device is disabled.\"}", response.body());
  }

  @Test
  void otherResponses() {
    assertEquals(204, RestJson.noContent().status());
    assertEquals("", RestJson.noContent().body());
    assertEquals(500, RestJson.internalError().status());
    assertEquals("InternalError", RestJson.internalError().headers().get("X-Amzn-Errortype"));
    RestJson.Response missing = RestJson.unknownOperation();
    assertEquals(404, missing.status());
    assertTrue(missing.headers().containsKey("Cache-Control"));
  }
}
