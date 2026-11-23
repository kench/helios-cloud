package tv.kenley.helios.cloud.control;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.GetDeviceLocationOutput;
import tv.kenley.helios.cloud.control.model.GpsConfiguredReceiver;
import tv.kenley.helios.cloud.control.model.GpsFix;
import tv.kenley.helios.cloud.control.model.GpsFixMode;
import tv.kenley.helios.cloud.control.model.GpsReceiver;
import tv.kenley.helios.cloud.control.model.GpsSatellites;
import tv.kenley.helios.cloud.control.model.GpsdState;

/**
 * A device's {@code gps} shadow (LLD 10), read into GetDeviceLocation's output. Field names are the
 * gps module's ({@code lat}, {@code alt_msl_m}, {@code last_fix}...). Anything missing or of the
 * wrong type is left out rather than failing the request, as in {@link StreamShadow}.
 *
 * <p>This is the device's exact position: nothing here logs it.
 *
 * @param document the whole shadow document, or {@code null} if the device has none
 */
record GpsShadow(Document document) {
  /** Whether the device has reported its gps state at all. */
  boolean reported() {
    return ShadowJson.string(document, "state", "reported", "gpsd").isPresent();
  }

  GetDeviceLocationOutput location() {
    Optional<Instant> reportedAt =
        ShadowJson.whole(document, "metadata", "reported", "gpsd", "timestamp")
            .map(Instant::ofEpochSecond);
    GetDeviceLocationOutput.Builder output =
        GetDeviceLocationOutput.builder()
            .gpsd(
                ShadowJson.string(document, "state", "reported", "gpsd")
                    .map(GpsShadow::gpsd)
                    .orElse(GpsdState.UNAVAILABLE))
            .receivers(receivers())
            .fix(
                ShadowJson.at(document, "state", "reported", "fix")
                    .map(GpsShadow::fix)
                    .orElseGet(GpsShadow::none))
            // The module always reports since; a shadow without it falls back to the report time.
            .since(
                ShadowJson.string(document, "state", "reported", "since")
                    .flatMap(GpsShadow::instant)
                    .or(() -> reportedAt)
                    .orElse(Instant.EPOCH));
    ShadowJson.at(document, "state", "reported", "last_fix")
        .filter(ShadowJson::isMap)
        .map(GpsShadow::fix)
        .ifPresent(output::lastFix);
    ShadowJson.at(document, "state", "reported", "satellites")
        .flatMap(GpsShadow::satellites)
        .ifPresent(output::satellites);
    ShadowJson.at(document, "state", "reported", "device")
        .flatMap(GpsShadow::configuredReceiver)
        .ifPresent(output::configuredReceiver);
    reportedAt.ifPresent(output::reportedAt);
    return output.build();
  }

  private List<GpsReceiver> receivers() {
    return ShadowJson.list(document, "state", "reported", "receivers").stream()
        .flatMap(r ->
            ShadowJson.string(r, "path").stream()
                .map(path -> {
                  GpsReceiver.Builder receiver = GpsReceiver.builder().path(path);
                  ShadowJson.string(r, "driver").ifPresent(receiver::driver);
                  ShadowJson.integer(r, "bps").ifPresent(receiver::bps);
                  ShadowJson.string(r, "usb_port").ifPresent(receiver::usbPort);
                  return receiver.build();
                }))
        .toList();
  }

  private static GpsdState gpsd(String state) {
    return state.equals("connected") ? GpsdState.CONNECTED : GpsdState.UNAVAILABLE;
  }

  private static GpsFix none() {
    return GpsFix.builder().mode(GpsFixMode.NONE).build();
  }

  /**
   * A fix; a mode of none carries no position, whatever else the shadow holds. The module sends
   * only none, 2d and 3d, so any other mode counts as none.
   */
  private static GpsFix fix(Document f) {
    GpsFixMode mode =
        ShadowJson.string(f, "mode")
            .flatMap(
                m -> GpsFixMode.values().stream().filter(k -> k.getValue().equals(m)).findFirst())
            .orElse(GpsFixMode.NONE);
    GpsFix.Builder fix = GpsFix.builder().mode(mode);
    if (mode.equals(GpsFixMode.NONE)) {
      return fix.build();
    }
    ShadowJson.string(f, "time").flatMap(GpsShadow::instant).ifPresent(fix::time);
    ShadowJson.number(f, "lat").ifPresent(v -> fix.latitude(v.doubleValue()));
    ShadowJson.number(f, "lon").ifPresent(v -> fix.longitude(v.doubleValue()));
    ShadowJson.number(f, "alt_msl_m").ifPresent(v -> fix.altitudeM(v.doubleValue()));
    ShadowJson.number(f, "speed_mps").ifPresent(v -> fix.speedMps(v.doubleValue()));
    ShadowJson.number(f, "track_deg").ifPresent(v -> fix.trackDeg(v.doubleValue()));
    ShadowJson.number(f, "climb_mps").ifPresent(v -> fix.climbMps(v.doubleValue()));
    ShadowJson.number(f, "eph_m").ifPresent(v -> fix.horizontalErrorM(v.doubleValue()));
    ShadowJson.number(f, "epv_m").ifPresent(v -> fix.verticalErrorM(v.doubleValue()));
    return fix.build();
  }

  private static Optional<GpsSatellites> satellites(Document s) {
    if (!ShadowJson.isMap(s) || s.asStringMap().isEmpty()) {
      return Optional.empty();
    }
    GpsSatellites.Builder satellites = GpsSatellites.builder();
    ShadowJson.integer(s, "used").ifPresent(satellites::used);
    ShadowJson.integer(s, "visible").ifPresent(satellites::visible);
    ShadowJson.number(s, "hdop").ifPresent(v -> satellites.hdop(v.doubleValue()));
    ShadowJson.number(s, "pdop").ifPresent(v -> satellites.pdop(v.doubleValue()));
    return Optional.of(satellites.build());
  }

  private static Optional<GpsConfiguredReceiver> configuredReceiver(Document d) {
    return ShadowJson.string(d, "path")
        .map(path -> {
          GpsConfiguredReceiver.Builder receiver =
              GpsConfiguredReceiver.builder()
                  .path(path)
                  .added(ShadowJson.bool(d, "added").orElse(false));
          ShadowJson.string(d, "error").ifPresent(receiver::error);
          return receiver.build();
        });
  }

  private static Optional<Instant> instant(String text) {
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
