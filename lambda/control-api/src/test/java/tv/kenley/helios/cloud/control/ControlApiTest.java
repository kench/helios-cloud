package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tv.kenley.helios.cloud.common.RestJson;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;

class ControlApiTest {
  static final String ID = DeviceDirectoryTest.ID;
  static final String BASE = "/v1/control/devices/" + ID;
  static final String CALLER = "arn:aws:sts::857247606239:assumed-role/helios-operator/kenley";

  private ControlServiceTest.FakeShadows shadows;
  private DeviceDirectory devices;
  private final List<String> audit = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private ControlApi api;

  @BeforeEach
  void setUp() {
    shadows = new ControlServiceTest.FakeShadows();
    shadows.documents.put(
        DeviceDirectoryTest.THING + "/stream",
        StreamShadowTest.fixture("stream-shadow-control.json"));
    devices = mock(DeviceDirectory.class);
    when(devices.get(ID))
        .thenReturn(
            Optional.of(
                DeviceSummary.builder()
                    .deviceId(ID)
                    .thingName(DeviceDirectoryTest.THING)
                    .role(DeviceRole.TRANSCODER)
                    .status(DeviceStatus.ACTIVE)
                    .build()));
    when(devices.list(null, 2)).thenReturn(new DeviceDirectory.Page(List.of(), null));
    api = api(new ControlService(devices, shadows));
  }

  private ControlApi api(ControlService service) {
    return new ControlApi(
        service,
        audit::add,
        errors::add,
        Clock.fixed(Instant.parse("2026-11-21T23:00:00Z"), ZoneOffset.UTC));
  }

  private RestJson.Response call(String method, String path, String body) {
    return call(method, path, body, Map.of());
  }

  private RestJson.Response call(
      String method, String path, String body, Map<String, String> query) {
    return api.handle(
        new RestJson.Request(
            method, path, body.getBytes(StandardCharsets.UTF_8), "198.51.100.7", "req-1", CALLER),
        query);
  }

  @Test
  void routesEveryOperation() {
    Map<String, String[]> routes =
        Map.of(
            "ListDevices", new String[] {"GET", "/v1/control/devices"},
            "GetDevice", new String[] {"GET", BASE},
            "ListInputs", new String[] {"GET", BASE + "/inputs"},
            "ListProfiles", new String[] {"GET", BASE + "/profiles"},
            "ListStreams", new String[] {"GET", BASE + "/streams"},
            "GetStream", new String[] {"GET", BASE + "/streams/pocket"},
            "UpdateStream", new String[] {"PATCH", BASE + "/streams/pocket"},
            "StartStream", new String[] {"POST", BASE + "/streams/pocket/start"},
            "StopStream", new String[] {"POST", BASE + "/streams/pocket/stop"},
            "SetStreamDestination", new String[] {"PUT", BASE + "/streams/pocket/destination"});
    routes.forEach((operation, route) ->
        assertEquals(operation, ControlApi.route(route[0], route[1]).orElseThrow().operation()));
    assertEquals(ID, ControlApi.route("GET", BASE + "/streams/pocket").orElseThrow().deviceId());
    assertEquals(
        "pocket", ControlApi.route("GET", BASE + "/streams/pocket").orElseThrow().streamId());
    assertTrue(ControlApi.route("DELETE", BASE).isEmpty());
    assertTrue(ControlApi.route("GET", BASE + "/streams/pocket/status").isEmpty(), "not yet");
    assertEquals(404, call("GET", "/v1/admin/devices", "").status());
    assertTrue(audit.isEmpty(), "unknown routes aren't operations");
  }

  @Test
  void answersReadsWith200() {
    RestJson.Response streams = call("GET", BASE + "/streams", "");
    assertEquals(200, streams.status());
    assertTrue(streams.body().contains("\"streamId\":\"pocket\""), streams.body());
    assertEquals(200, call("GET", BASE, "").status());
    assertEquals(200, call("GET", BASE + "/inputs", "").status());
    assertEquals(200, call("GET", BASE + "/profiles", "").status());
    assertEquals(200, call("GET", BASE + "/streams/pocket", "").status());
    assertEquals(200, call("GET", "/v1/control/devices", "", Map.of("maxResults", "2")).status());
  }

  @Test
  void answersChangesWith202AndAudits() {
    assertEquals(202, call("POST", BASE + "/streams/pocket/start", "").status());
    assertEquals(
        202, call("POST", BASE + "/streams/pocket/stop", "{\"clientToken\":\"t-1\"}").status());
    assertEquals(202, call("PATCH", BASE + "/streams/pocket", "{\"profile\":\"hevc\"}").status());
    RestJson.Response destination =
        call(
            "PUT",
            BASE + "/streams/pocket/destination",
            "{\"url\":\"srt://other.example.com:4001?latency=1000\"}");
    assertEquals(202, destination.status());
    assertTrue(destination.body().contains("\"previous\""), destination.body());
    String line = audit.getLast();
    assertTrue(line.startsWith("{\"type\":\"control-audit\""), line);
    for (String field :
        List.of(
            "\"time\":\"2026-11-21T23:00:00Z\"",
            "\"requestId\":\"req-1\"",
            "\"operation\":\"SetStreamDestination\"",
            "\"caller\":\"" + CALLER + "\"",
            "\"sourceIp\":\"198.51.100.7\"",
            "\"deviceId\":\"" + ID + "\"",
            "\"streamId\":\"pocket\"",
            "\"destination\":\"srt://other.example.com:4001?latency=1000\"",
            "\"result\":\"ok\"")) {
      assertTrue(line.contains(field), field + " in " + line);
    }
  }

  @Test
  void validatesInputsAndReportsModelledErrors() {
    RestJson.Response badId = call("GET", "/v1/control/devices/not-a-device", "");
    assertEquals(400, badId.status());
    assertEquals("ValidationException", badId.headers().get("X-Amzn-Errortype"));
    assertEquals(
        400, call("GET", "/v1/control/devices", "", Map.of("maxResults", "lots")).status());
    assertEquals(400, call("GET", "/v1/control/devices", "", Map.of("maxResults", "0")).status());
    assertEquals(400, call("PATCH", BASE + "/streams/pocket", "{not json").status());
    assertEquals(
        400,
        call("PATCH", BASE + "/streams/pocket", "{\"bitrate\":{\"overrideKbps\":5}}").status());

    RestJson.Response invalid =
        call("PUT", BASE + "/streams/pocket/destination", "{\"url\":\"srt://h:1?mode=listener\"}");
    assertEquals(400, invalid.status());
    assertEquals("InvalidDestination", invalid.headers().get("X-Amzn-Errortype"));
    assertTrue(invalid.body().contains("\"part\":\"mode\""), invalid.body());
    assertTrue(audit.getLast().contains("\"result\":\"InvalidDestination\""));

    RestJson.Response missing = call("GET", BASE + "/streams/nope", "");
    assertEquals(404, missing.status());
    assertEquals("StreamNotFound", missing.headers().get("X-Amzn-Errortype"));
  }

  @Test
  void hidesUnexpectedFailures() {
    Shadows broken =
        new Shadows() {
          @Override
          public Optional<byte[]> get(String thingName, String name) {
            throw new IllegalStateException("throttled");
          }

          @Override
          public long update(String thingName, String name, byte[] document) {
            throw new IllegalStateException("throttled");
          }
        };
    api = api(new ControlService(devices, broken));
    RestJson.Response response = call("GET", BASE + "/streams", "");
    assertEquals(500, response.status());
    assertEquals("InternalError", response.headers().get("X-Amzn-Errortype"));
    assertFalse(response.body().contains("throttled"), "no detail in the body");
    assertTrue(errors.getLast().contains("throttled"), "but in the log");
    assertTrue(audit.getLast().contains("\"result\":\"InternalError\""));
  }
}
