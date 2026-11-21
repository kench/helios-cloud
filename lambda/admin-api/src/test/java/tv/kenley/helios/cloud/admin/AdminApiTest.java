package tv.kenley.helios.cloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import tv.kenley.helios.cloud.common.RestJson;
import tv.kenley.helios.cloud.model.DeviceDescription;
import tv.kenley.helios.cloud.model.DeviceNotFound;
import tv.kenley.helios.cloud.model.DeviceStatus;
import tv.kenley.helios.cloud.model.RegisterDeviceInput;
import tv.kenley.helios.cloud.model.SetDeviceStatusInput;

@ExtendWith(MockitoExtension.class)
final class AdminApiTest {
  private static final String ID = "d-01JD5VZ6R1H8P3S0W2Y4A6C8E0";
  private static final String CALLER =
      "arn:aws:sts::857247606239:assumed-role/helios-provisioning/session";
  private static final DeviceDescription DEVICE =
      DeviceItems.describe(
          DeviceItems.item(DeviceItemsTest.newDevice(Optional.empty(), Optional.empty())));

  @Mock private AdminService service;

  private final List<String> audit = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private AdminApi api;

  @BeforeEach
  void setUp() {
    api =
        new AdminApi(
            service, audit::add, errors::add, Clock.fixed(DeviceItemsTest.NOW, ZoneOffset.UTC));
  }

  private RestJson.Response call(String method, String path, String body) {
    return api.handle(
        new RestJson.Request(
            method,
            path,
            body.getBytes(StandardCharsets.UTF_8),
            "203.0.113.7",
            "request-1",
            CALLER));
  }

  private static ObjectNode json(String body) {
    return Node.parse(body).expectObjectNode();
  }

  private ObjectNode lastAudit() {
    return json(audit.getLast());
  }

  @Test
  void registersDevice() {
    ArgumentCaptor<RegisterDeviceInput> input = ArgumentCaptor.forClass(RegisterDeviceInput.class);
    when(service.register(input.capture())).thenReturn(DEVICE);
    String body =
        """
        {"thingName": "t-01JD5VY2K7M9Q4R6T8V0X2Z4B6", "role": "scanner",
         "macs": ["00:d0:93:4a:ba:ae"], "pin": "%s",
         "token": {"tokenLabel": "helios", "keyLabel": "device-key"}}\
        """
            .formatted(DeviceItemsTest.PIN);

    RestJson.Response response = call("POST", AdminApi.DEVICES_PATH, body);

    assertEquals(201, response.status());
    assertEquals("no-store", response.headers().get("Cache-Control"));
    ObjectNode device = json(response.body()).expectObjectMember("device");
    assertEquals(ID, device.expectStringMember("deviceId").getValue());
    assertFalse(device.containsMember("pin"));
    assertEquals(DeviceItemsTest.PIN, input.getValue().getPin());
    assertEquals("RegisterDevice", lastAudit().expectStringMember("operation").getValue());
    assertEquals(CALLER, lastAudit().expectStringMember("caller").getValue());
    assertEquals(ID, lastAudit().expectStringMember("deviceId").getValue());
    assertEquals("ok", lastAudit().expectStringMember("result").getValue());
  }

  @Test
  void thePinNeverReachesTheResponseOrTheLogs() {
    when(service.register(any())).thenReturn(DEVICE);

    RestJson.Response response =
        call(
            "POST",
            AdminApi.DEVICES_PATH,
            """
            {"thingName": "t-1", "role": "scanner", "macs": ["00:d0:93:4a:ba:ae"],
             "pin": "%s", "token": {"tokenLabel": "helios", "keyLabel": "device-key"}}\
            """
                .formatted(DeviceItemsTest.PIN));

    assertFalse(response.body().contains(DeviceItemsTest.PIN));
    for (String line : audit) {
      assertFalse(line.contains(DeviceItemsTest.PIN), line);
    }
    assertTrue(errors.isEmpty());
  }

  @Test
  void getsDeviceByItsPathLabel() {
    when(service.get(ID)).thenReturn(DEVICE);

    RestJson.Response response = call("GET", AdminApi.DEVICES_PATH + "/" + ID, "");

    assertEquals(200, response.status());
    assertEquals(
        ID,
        json(response.body())
            .expectObjectMember("device")
            .expectStringMember("deviceId")
            .getValue());
  }

  @Test
  void invalidDeviceIdIsValidationError() {
    RestJson.Response response = call("GET", AdminApi.DEVICES_PATH + "/nope", "");

    assertEquals(400, response.status());
    assertEquals("ValidationException", response.headers().get("X-Amzn-Errortype"));
    verifyNoInteractions(service);
  }

  @Test
  void setsTheStatus() {
    ArgumentCaptor<SetDeviceStatusInput> input =
        ArgumentCaptor.forClass(SetDeviceStatusInput.class);
    when(service.setStatus(input.capture())).thenReturn(DEVICE);

    RestJson.Response response =
        call(
            "PUT",
            AdminApi.DEVICES_PATH + "/" + ID + "/status",
            "{\"status\": \"disabled\", \"reason\": \"lost\"}");

    assertEquals(200, response.status());
    assertEquals(ID, input.getValue().getDeviceId());
    assertEquals(DeviceStatus.DISABLED, input.getValue().getStatus());
  }

  @Test
  void deletesWithNoContent() {
    RestJson.Response response = call("DELETE", AdminApi.DEVICES_PATH + "/" + ID, "");

    assertEquals(204, response.status());
    assertEquals("", response.body());
    verify(service).delete(ID);
  }

  @Test
  void modelledErrorsUseRestJson1() {
    doThrow(DeviceNotFound.builder().message("No device record.").build()).when(service).delete(ID);

    RestJson.Response response = call("DELETE", AdminApi.DEVICES_PATH + "/" + ID, "");

    assertEquals(404, response.status());
    assertEquals("DeviceNotFound", response.headers().get("X-Amzn-Errortype"));
    assertEquals("DeviceNotFound", lastAudit().expectStringMember("result").getValue());
  }

  @Test
  void unexpectedFailuresAreInternalErrors() {
    when(service.get(ID)).thenThrow(new IllegalStateException("DynamoDB down"));

    RestJson.Response response = call("GET", AdminApi.DEVICES_PATH + "/" + ID, "");

    assertEquals(500, response.status());
    assertTrue(errors.getFirst().contains("DynamoDB down"));
  }

  @Test
  void otherRoutesAreNotFound() {
    assertEquals(404, call("PATCH", AdminApi.DEVICES_PATH + "/" + ID, "{}").status());
    assertEquals(404, call("GET", AdminApi.DEVICES_PATH, "").status());
    assertEquals(404, call("POST", AdminApi.DEVICES_PATH + "/" + ID + "/status", "{}").status());
    assertEquals(404, call("PUT", AdminApi.DEVICES_PATH + "/" + ID + "/pin", "{}").status());
    assertTrue(audit.isEmpty());
  }

  @Test
  void unsignedCallerIsAuditedAsUnknown() {
    when(service.get(ID)).thenReturn(DEVICE);

    api.handle(
        new RestJson.Request(
            "GET", AdminApi.DEVICES_PATH + "/" + ID, new byte[0], "203.0.113.7", "r", null));

    assertEquals("unknown", lastAudit().expectStringMember("caller").getValue());
  }

  @Test
  void theHandlerReadsItsTablesAndPassesRequestsThrough() {
    assertEquals(
        new AdminStore.Tables("d", "m"),
        AdminApiHandler.tables(Map.of("DEVICES_TABLE", "d", "MAC_INDEX_TABLE", "m")));
    assertThrows(
        IllegalArgumentException.class, () -> AdminApiHandler.tables(Map.of("DEVICES_TABLE", "d")));
    assertThrows(IllegalArgumentException.class, AdminApiHandler::new);

    when(service.get(ID)).thenReturn(DEVICE);
    Context context = org.mockito.Mockito.mock(Context.class);
    when(context.getAwsRequestId()).thenReturn("request-1");
    var response =
        new AdminApiHandler(api)
            .handleRequest(
                new APIGatewayProxyRequestEvent()
                    .withHttpMethod("GET")
                    .withPath(AdminApi.DEVICES_PATH + "/" + ID),
                context);

    assertEquals(200, response.getStatusCode());
  }
}
