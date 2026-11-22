package tv.kenley.helios.cloud.control;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.DeleteWifiNetworkOutput;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceUnreachable;
import tv.kenley.helios.cloud.control.model.ListWifiNetworksOutput;
import tv.kenley.helios.cloud.control.model.SetWifiNetworkInput;
import tv.kenley.helios.cloud.control.model.SetWifiNetworkOutput;
import tv.kenley.helios.cloud.control.model.WifiNetwork;
import tv.kenley.helios.cloud.control.model.WifiNetworkRefused;
import tv.kenley.helios.cloud.control.model.WifiSecurity;

/**
 * The Control API's Wi-Fi operations (LLD 3, section 10): the networks from the device's {@code
 * net} shadow, and changes as AWS IoT command executions, answered by the device. A passphrase is
 * sealed to the device's key from its {@code helios} shadow, since AWS keeps a command's
 * parameters; the plaintext exists only in this call and is never logged.
 */
final class WifiControl {
  static final String NET_SHADOW = "net";
  static final String SET_COMMAND = "helios-net-wifi-set";
  static final String REMOVE_COMMAND = "helios-net-wifi-remove";

  /** The execution's timeout: well inside API Gateway's 29 s and the Lambda's 15 s. */
  static final int TIMEOUT_SECONDS = 10;

  static final Duration POLL = Duration.ofMillis(400);

  /** How long after the timeout to keep looking, for an answer on its way. */
  static final Duration GRACE = Duration.ofSeconds(1);

  /** Waits between polls; tests don't. */
  interface Pause {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final DeviceDirectory devices;
  private final Shadows shadows;
  private final Commands commands;
  private final Clock clock;
  private final Pause pause;

  WifiControl(
      DeviceDirectory devices, Shadows shadows, Commands commands, Clock clock, Pause pause) {
    this.devices = devices;
    this.shadows = shadows;
    this.commands = commands;
    this.clock = clock;
    this.pause = pause;
  }

  ListWifiNetworksOutput list(String deviceId) {
    String thing = thing(deviceId);
    Document net =
        shadows
            .get(thing, NET_SHADOW)
            .map(ShadowJson::parse)
            .flatMap(document -> ShadowJson.at(document, "state", "reported", "wifi"))
            .orElseThrow(() ->
                DeviceStateUnavailable.builder()
                    .message(
                        "The device doesn't report Wi-Fi networks (no net module, or its Helios"
                            + " predates Wi-Fi).")
                    .build());
    List<WifiNetwork> networks = new ArrayList<>();
    for (Document network : ShadowJson.list(net, "networks")) {
      network(network).ifPresent(networks::add);
    }
    return ListWifiNetworksOutput.builder().networks(networks).build();
  }

  SetWifiNetworkOutput set(SetWifiNetworkInput input) {
    String thing = thing(input.getDeviceId());
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("ssid", input.getSsid());
    args.put("hidden", input.isHidden());
    if (input.getPassphrase() != null) {
      String key = secretsKey(thing);
      Sealing.Sealed sealed =
          Sealing.seal(
              key,
              Sealing.info(thing),
              ("wifi.passphrase " + input.getSsid()).getBytes(StandardCharsets.UTF_8),
              input.getPassphrase().getBytes(StandardCharsets.UTF_8));
      Map<String, Object> wire = new LinkedHashMap<>();
      wire.put("enc", sealed.enc());
      wire.put("ct", sealed.ct());
      args.put("passphrase", Map.of("sealed", wire));
    }
    Document result = run(thing, SET_COMMAND, args);
    WifiNetwork network =
        ShadowJson.at(result, "network")
            .flatMap(WifiControl::network)
            .orElseThrow(() -> new IllegalStateException("the device's answer has no network"));
    return SetWifiNetworkOutput.builder().network(network).build();
  }

  DeleteWifiNetworkOutput delete(String deviceId, String ssid) {
    String thing = thing(deviceId);
    Document result = run(thing, REMOVE_COMMAND, Map.of("ssid", ssid));
    return DeleteWifiNetworkOutput.builder()
        .removed(ShadowJson.bool(result, "removed").orElse(false))
        .build();
  }

  /** The device's secrets key from its {@code helios} shadow, if it has one in our suite. */
  private String secretsKey(String thing) {
    Optional<Document> key =
        shadows
            .get(thing, ControlService.HELIOS_SHADOW)
            .map(ShadowJson::parse)
            .flatMap(document -> ShadowJson.at(document, "state", "reported", "secrets_key"));
    if (key.isEmpty()
        || !ShadowJson.string(key.get(), "suite").filter(Sealing.SUITE::equals).isPresent()) {
      throw refused(
          "no_secrets_key",
          "The device doesn't report a key to seal the passphrase to (its Helios predates sealed"
              + " secrets, or its token can't make one).");
    }
    return ShadowJson.string(key.get(), "public")
        .orElseThrow(
            () -> refused("no_secrets_key", "The device's secrets key has no public key."));
  }

  /**
   * Runs {@code commandId} on the device with {@code args} and returns its result.
   *
   * @throws DeviceUnreachable if it doesn't answer in time
   * @throws WifiNetworkRefused if it refuses
   */
  private Document run(String thing, String commandId, Map<String, Object> args) {
    String encoded = Base64.getEncoder().encodeToString(ShadowJson.bytes(args));
    String execution = commands.start(thing, commandId, encoded, TIMEOUT_SECONDS);
    Instant until = clock.instant().plusSeconds(TIMEOUT_SECONDS).plus(GRACE);
    while (true) {
      Optional<Commands.Execution> state = commands.get(thing, execution);
      if (state.isPresent() && state.get().finished()) {
        return outcome(state.get());
      }
      if (!clock.instant().isBefore(until)) {
        throw unreachable();
      }
      try {
        pause.sleep(POLL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw unreachable();
      }
    }
  }

  private static Document outcome(Commands.Execution execution) {
    return switch (execution.status()) {
      case "SUCCEEDED" ->
          execution.result() == null
              ? ShadowJson.parse("{}".getBytes(StandardCharsets.UTF_8))
              : ShadowJson.parse(execution.result().getBytes(StandardCharsets.UTF_8));
      case "TIMED_OUT" -> throw unreachable();
      default -> throw refusal(execution);
    };
  }

  /** The device's error as a refusal, its code lower-cased as the reason. */
  private static RuntimeException refusal(Commands.Execution execution) {
    String code = execution.reasonCode() == null ? "INTERNAL" : execution.reasonCode();
    if ("TIMEOUT".equals(code)) {
      return unreachable();
    }
    String reason =
        switch (code) {
          // A Helios without the net module's Wi-Fi commands.
          case "UNKNOWN_COMMAND" -> "unsupported";
          case "INTERNAL", "MODULE_UNAVAILABLE" -> "apply_failed";
          default -> code.toLowerCase(Locale.ROOT);
        };
    String message =
        execution.reasonDescription() == null
            ? "The device refused the change (" + code + ")."
            : execution.reasonDescription();
    return refused(reason, message);
  }

  private static Optional<WifiNetwork> network(Document network) {
    Optional<String> ssid = ShadowJson.string(network, "ssid");
    Optional<String> security = ShadowJson.string(network, "security");
    if (ssid.isEmpty() || security.isEmpty()) {
      return Optional.empty();
    }
    WifiSecurity kind =
        switch (security.get()) {
          case "open" -> WifiSecurity.OPEN;
          case "personal" -> WifiSecurity.PERSONAL;
          default -> null;
        };
    if (kind == null) {
      return Optional.empty();
    }
    return Optional.of(
        WifiNetwork.builder()
            .ssid(ssid.get())
            .security(kind)
            .hidden(ShadowJson.bool(network, "hidden").orElse(false))
            .build());
  }

  private String thing(String deviceId) {
    return devices
        .get(deviceId)
        .orElseThrow(() -> DeviceNotFound.builder().message("No device " + deviceId + ".").build())
        .getThingName();
  }

  private static WifiNetworkRefused refused(String reason, String message) {
    return WifiNetworkRefused.builder().reason(reason).message(message).build();
  }

  private static DeviceUnreachable unreachable() {
    return DeviceUnreachable.builder()
        .message(
            "The device didn't answer in "
                + TIMEOUT_SECONDS
                + " s: it may be offline. Nothing was"
                + " changed.")
        .build();
  }
}
