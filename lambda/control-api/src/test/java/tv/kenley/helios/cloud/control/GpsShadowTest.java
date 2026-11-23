package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tv.kenley.helios.cloud.control.model.GetDeviceLocationOutput;
import tv.kenley.helios.cloud.control.model.GpsFix;
import tv.kenley.helios.cloud.control.model.GpsFixMode;
import tv.kenley.helios.cloud.control.model.GpsReceiver;
import tv.kenley.helios.cloud.control.model.GpsdState;

/** The gps shadow (LLD 10) read into GetDeviceLocation. Positions here are made up. */
class GpsShadowTest {
  static GpsShadow shadow(String name) {
    return new GpsShadow(ShadowJson.parse(StreamShadowTest.fixture(name)));
  }

  private static GpsShadow shadow(byte[] json) {
    return new GpsShadow(ShadowJson.parse(json));
  }

  @Test
  void readsTheCurrentFix() {
    GpsShadow shadow = shadow("gps-shadow-fix.json");
    assertTrue(shadow.reported());
    GetDeviceLocationOutput location = shadow.location();
    assertEquals(GpsdState.CONNECTED, location.getGpsd());
    assertEquals(
        List.of(
            GpsReceiver.builder()
                .path("/dev/ttyACM0")
                .driver("NMEA0183")
                .bps(9600)
                .usbPort("3-1.2")
                .build(),
            GpsReceiver.builder().path("/dev/ttyUSB9").build()),
        location.getReceivers(),
        "a receiver without a path is left out");
    assertEquals(
        GpsFix.builder()
            .mode(GpsFixMode.THREE_D)
            .time(Instant.parse("2026-11-22T20:15:30Z"))
            .latitude(12.345678)
            .longitude(-98.765432)
            .altitudeM(41.5)
            .speedMps(0.12)
            .trackDeg(270.0)
            .climbMps(-0.01)
            .horizontalErrorM(4.2)
            .verticalErrorM(7.9)
            .build(),
        location.getFix());
    assertNull(location.getLastFix());
    assertEquals(9, location.getSatellites().getUsed());
    assertEquals(14, location.getSatellites().getVisible());
    assertEquals(0.9, location.getSatellites().getHdop());
    assertEquals(1.6, location.getSatellites().getPdop());
    assertEquals(Instant.parse("2026-11-22T20:10:02Z"), location.getSince());
    assertTrue(location.getConfiguredReceiver().isAdded());
    assertNull(location.getConfiguredReceiver().getError());
    assertEquals(
        Instant.parse("2026-11-22T20:15:30Z"),
        location.getReportedAt(),
        "from the shadow's metadata");
  }

  @Test
  void keepsTheLastFixWhenTheFixIsLost() {
    GetDeviceLocationOutput location = shadow("gps-shadow-lost.json").location();
    assertEquals(
        GpsFix.builder().mode(GpsFixMode.NONE).build(),
        location.getFix(),
        "mode none carries no position, whatever else the shadow holds");
    assertEquals(
        GpsFix.builder()
            .mode(GpsFixMode.TWO_D)
            .time(Instant.parse("2026-11-22T19:00:00Z"))
            .latitude(12.3)
            .longitude(-98.7)
            .build(),
        location.getLastFix());
    assertTrue(location.getReceivers().isEmpty());
    assertNull(location.getSatellites(), "an empty satellites object is left out");
    assertFalse(location.getConfiguredReceiver().isAdded());
    assertEquals("gpsd: ERROR", location.getConfiguredReceiver().getError());
    assertNull(location.getReportedAt(), "no metadata");
  }

  @Test
  void skipsValuesOfTheWrongType() {
    GetDeviceLocationOutput location =
        shadow(
                bytes(
                    "{\"state\":{\"reported\":{\"gpsd\":\"connected\",\"fix\":{\"mode\":"
                        + "\"2d\",\"lat\":\"north\",\"lon\":3.5,\"time\":\"soon\"},"
                        + "\"receivers\":[{\"path\":\"/dev/ttyACM0\",\"bps\":\"fast\"}],"
                        + "\"since\":\"2026-11-22T20:00:00Z\"}}}"))
            .location();
    assertNull(location.getFix().getLatitude(), "not a number");
    assertEquals(3.5, location.getFix().getLongitude());
    assertNull(location.getFix().getTime(), "not a time");
    assertNull(location.getReceivers().getFirst().getBps());
  }

  @Test
  void toleratesWhatAnOlderOrOddDeviceSends() {
    assertFalse(new GpsShadow(null).reported(), "no shadow");
    assertFalse(shadow(bytes("{\"state\":{\"reported\":{\"fix\":{}}}}")).reported(), "no gpsd");
    GetDeviceLocationOutput location =
        shadow(
                bytes(
                    "{\"state\":{\"reported\":{\"gpsd\":\"restarting\",\"fix\":{\"mode\":\"4d\","
                        + "\"lat\":1.5},\"since\":\"yesterday\",\"device\":{\"added\":true}"
                        + "}},\"metadata\":{\"reported\":{\"gpsd\":{\"timestamp\":1795378530}}}}"))
            .location();
    assertEquals(GpsdState.UNAVAILABLE, location.getGpsd(), "an unknown gpsd state");
    assertEquals(
        GpsFix.builder().mode(GpsFixMode.NONE).build(),
        location.getFix(),
        "an unknown mode counts as none, without a position");
    assertEquals(
        Instant.parse("2026-11-22T20:15:30Z"),
        location.getSince(),
        "a bad since falls back to the report time");
    assertNull(location.getConfiguredReceiver(), "a configured receiver without a path");
    assertEquals(
        GpsFixMode.NONE,
        shadow(bytes("{\"state\":{\"reported\":{\"gpsd\":\"connected\"}}}"))
            .location()
            .getFix()
            .getMode(),
        "no fix at all");
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
