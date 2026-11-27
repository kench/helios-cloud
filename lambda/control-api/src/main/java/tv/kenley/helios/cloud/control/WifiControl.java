package tv.kenley.helios.cloud.control;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.DeleteWifiNetworkOutput;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
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
 * parameters. Once the device has taken it, the cloud keeps the passphrase (the settings store,
 * encrypted at rest) so operators can read it back; it's never logged.
 */
final class WifiControl {
  static final String NET_SHADOW = "net";
  static final String SET_COMMAND = "helios-net-wifi-set-v1";
  static final String REMOVE_COMMAND = "helios-net-wifi-remove-v1";

  private final DeviceDirectory devices;
  private final Shadows shadows;
  private final CommandRunner runner;
  private final SettingsStore settings;

  WifiControl(
      DeviceDirectory devices,
      Shadows shadows,
      Commands commands,
      Clock clock,
      CommandRunner.Pause pause) {
    this(devices, shadows, new CommandRunner(commands, clock, pause), null);
  }

  /** {@code settings} keeps passphrases for operators; {@code null} keeps none. */
  WifiControl(
      DeviceDirectory devices, Shadows shadows, CommandRunner runner, SettingsStore settings) {
    this.devices = devices;
    this.shadows = shadows;
    this.runner = runner;
    this.settings = settings;
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
    Map<String, Document> kept =
        settings == null ? Map.of() : settings.all(deviceId, SettingsStore.wifi(""));
    List<WifiNetwork> networks = new ArrayList<>();
    for (Document network : ShadowJson.list(net, "networks")) {
      network(network, kept).ifPresent(networks::add);
    }
    return ListWifiNetworksOutput.builder().networks(networks).build();
  }

  SetWifiNetworkOutput set(SetWifiNetworkInput input) {
    String thing = thing(input.getDeviceId());
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("ssid", input.getSsid());
    args.put("hidden", input.isHidden());
    if (input.getPassphrase() != null) {
      String key =
          Sealing.deviceKey(shadows, thing)
              .orElseThrow(() ->
                  refused(
                      "no_secrets_key",
                      "The device doesn't report a key to seal the passphrase to (its Helios"
                          + " predates sealed secrets, or its token can't make one)."));
      args.put(
          "passphrase",
          Sealing.sealedArg(
              key,
              thing,
              "wifi.passphrase " + input.getSsid(),
              input.getPassphrase().getBytes(StandardCharsets.UTF_8)));
    }
    Document result = run(thing, SET_COMMAND, args);
    WifiNetwork network =
        ShadowJson.at(result, "network")
            .flatMap(n -> network(n, Map.of()))
            .orElseThrow(() -> new IllegalStateException("the device's answer has no network"));
    if (settings != null) {
      if (input.getPassphrase() == null) {
        settings.delete(input.getDeviceId(), SettingsStore.wifi(input.getSsid()));
      } else {
        settings.put(
            input.getDeviceId(),
            SettingsStore.wifi(input.getSsid()),
            Map.of("passphrase", input.getPassphrase()));
        network = network.toBuilder().passphrase(input.getPassphrase()).build();
      }
    }
    return SetWifiNetworkOutput.builder().network(network).build();
  }

  DeleteWifiNetworkOutput delete(String deviceId, String ssid) {
    String thing = thing(deviceId);
    Document result = run(thing, REMOVE_COMMAND, Map.of("ssid", ssid));
    if (settings != null) {
      settings.delete(deviceId, SettingsStore.wifi(ssid));
    }
    return DeleteWifiNetworkOutput.builder()
        .removed(ShadowJson.bool(result, "removed").orElse(false))
        .build();
  }

  /**
   * Runs {@code commandId} on the device with {@code args} and returns its result.
   *
   * @throws tv.kenley.helios.cloud.control.model.DeviceUnreachable if it doesn't answer in time
   * @throws WifiNetworkRefused if it refuses
   */
  private Document run(String thing, String commandId, Map<String, Object> args) {
    Commands.Execution execution = runner.run(thing, commandId, args);
    if ("SUCCEEDED".equals(execution.status())) {
      return CommandRunner.result(execution);
    }
    throw refusal(execution);
  }

  /** The device's error as a refusal, its code lower-cased as the reason. */
  private static RuntimeException refusal(Commands.Execution execution) {
    String code = execution.reasonCode() == null ? "INTERNAL" : execution.reasonCode();
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

  /** A network as the device reports it, with the passphrase the cloud kept, if any. */
  private static Optional<WifiNetwork> network(Document network, Map<String, Document> kept) {
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
    WifiNetwork.Builder builder =
        WifiNetwork.builder()
            .ssid(ssid.get())
            .security(kind)
            .hidden(ShadowJson.bool(network, "hidden").orElse(false));
    if (kind == WifiSecurity.PERSONAL) {
      Optional.ofNullable(kept.get(ssid.get()))
          .flatMap(k -> ShadowJson.string(k, "passphrase"))
          .ifPresent(builder::passphrase);
    }
    return Optional.of(builder.build());
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
}
