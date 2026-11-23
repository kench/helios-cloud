package tv.kenley.helios.cloud.control;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.GetDeviceUplinksOutput;
import tv.kenley.helios.cloud.control.model.PublicAddressCheck;
import tv.kenley.helios.cloud.control.model.Uplink;
import tv.kenley.helios.cloud.control.model.UplinkBonding;
import tv.kenley.helios.cloud.control.model.UplinkBondingState;
import tv.kenley.helios.cloud.control.model.UplinkWifi;

/**
 * A device's {@code net} shadow (LLD 3, section 7) and the SRTLA outputs in its {@code stream}
 * shadow, read into GetDeviceUplinks' output. Field names are the device's ({@code oper_state},
 * {@code default_routes}, {@code public_ipv4}...). Anything missing or of the wrong type is left
 * out rather than failing the request, as in {@link StreamShadow}.
 *
 * <p>Public addresses are personal data: the address is put in the output only when asked for, and
 * nothing here logs it.
 *
 * @param net the whole {@code net} shadow document, or {@code null} if the device has none
 * @param stream the whole {@code stream} shadow document, or {@code null}
 */
record UplinksShadow(Document net, Document stream) {
  /** Whether the device has reported its interfaces at all. */
  boolean reported() {
    return ShadowJson.at(net, "state", "reported", "interfaces")
        .filter(ShadowJson::isMap)
        .isPresent();
  }

  GetDeviceUplinksOutput uplinks(boolean includePublicAddresses) {
    Map<String, Document> interfaces =
        new TreeMap<>(ShadowJson.map(net, "state", "reported", "interfaces"));
    interfaces.values().removeIf(i -> !ShadowJson.isMap(i));
    Map<String, String> publicAddresses = publicAddresses(interfaces);
    Map<String, List<UplinkBonding>> bonding = bonding(interfaces);
    List<Uplink> uplinks = new ArrayList<>();
    interfaces.forEach((name, i) ->
        uplinks.add(
            uplink(
                name,
                i,
                publicAddresses,
                bonding.getOrDefault(name, List.of()),
                includePublicAddresses)));
    GetDeviceUplinksOutput.Builder output = GetDeviceUplinksOutput.builder().uplinks(uplinks);
    // The device reports `truncated` with every full report of its interfaces.
    ShadowJson.whole(net, "metadata", "reported", "truncated", "timestamp")
        .map(Instant::ofEpochSecond)
        .ifPresent(output::reportedAt);
    return output.build();
  }

  private static Uplink uplink(
      String name,
      Document i,
      Map<String, String> publicAddresses,
      List<UplinkBonding> bonding,
      boolean includePublicAddresses) {
    Uplink.Builder uplink =
        Uplink.builder()
            .interfaceMember(name)
            .kind(ShadowJson.string(i, "kind").orElse("other"))
            .operState(ShadowJson.string(i, "oper_state").orElse("unknown"))
            .ipv4(ShadowJson.strings(i, "ipv4"))
            .sourceRouted(ShadowJson.bool(i, "source_route", "rule").orElse(false))
            .bonding(bonding);
    ShadowJson.bool(i, "carrier").ifPresent(uplink::carrier);
    ShadowJson.list(i, "default_routes").stream()
        .filter(r -> ShadowJson.string(r, "family").filter("ipv4"::equals).isPresent())
        .flatMap(r -> ShadowJson.integer(r, "metric").stream())
        .min(Integer::compare)
        .ifPresent(uplink::defaultRouteMetric);
    ShadowJson.at(i, "wifi")
        .filter(ShadowJson::isMap)
        .map(w -> {
          UplinkWifi.Builder wifi = UplinkWifi.builder();
          ShadowJson.string(w, "ssid").ifPresent(wifi::ssid);
          ShadowJson.integer(w, "signal_dbm").ifPresent(wifi::signalDbm);
          ShadowJson.integer(w, "frequency_mhz").ifPresent(wifi::frequencyMhz);
          return wifi.build();
        })
        .ifPresent(uplink::wifi);
    ShadowJson.at(i, "public_ipv4")
        .filter(ShadowJson::isMap)
        .map(p -> publicCheck(name, p, publicAddresses, includePublicAddresses))
        .ifPresent(uplink::publicIpv4);
    return uplink.build();
  }

  private static PublicAddressCheck publicCheck(
      String name,
      Document p,
      Map<String, String> publicAddresses,
      boolean includePublicAddresses) {
    String mine = publicAddresses.get(name);
    List<String> shares =
        mine == null
            ? List.of()
            : publicAddresses.entrySet().stream()
                .filter(e -> !e.getKey().equals(name) && e.getValue().equals(mine))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    PublicAddressCheck.Builder check = PublicAddressCheck.builder().sharesPublicAddressWith(shares);
    if (includePublicAddresses) {
      ShadowJson.string(p, "address").ifPresent(check::address);
    }
    ShadowJson.string(p, "checked_at").flatMap(UplinksShadow::instant).ifPresent(check::checkedAt);
    ShadowJson.string(p, "error").ifPresent(check::error);
    ShadowJson.string(p, "failed_at").flatMap(UplinksShadow::instant).ifPresent(check::failedAt);
    return check.build();
  }

  /**
   * Each interface's public address from its latest check, when that check succeeded: a failed
   * check's kept address doesn't count as shared, as on the device (LLD 3, section 3.5.1).
   */
  private static Map<String, String> publicAddresses(Map<String, Document> interfaces) {
    Map<String, String> addresses = new HashMap<>();
    interfaces.forEach((name, i) -> {
      if (ShadowJson.string(i, "public_ipv4", "error").isPresent()) {
        return;
      }
      ShadowJson.string(i, "public_ipv4", "address")
          .ifPresent(address -> addresses.put(name, address));
    });
    return addresses;
  }

  /**
   * What each running SRTLA output does with each interface: its {@code uplinks} are the local
   * addresses it bonds (matched to interfaces by their IPv4 addresses), its {@code standby} names
   * interfaces left out.
   */
  private Map<String, List<UplinkBonding>> bonding(Map<String, Document> interfaces) {
    Map<String, String> byAddress = new HashMap<>();
    interfaces.forEach((name, i) ->
        ShadowJson.strings(i, "ipv4").stream()
            .map(a -> a.split("/", 2)[0])
            .forEach(address -> byAddress.putIfAbsent(address, name)));
    Map<String, List<UplinkBonding>> bonding = new TreeMap<>();
    new TreeMap<>(ShadowJson.map(stream, "state", "reported", "outputs"))
        .forEach((output, o) -> {
          if (!ShadowJson.isMap(o)
              || !ShadowJson.has(o, "uplinks")
              || ShadowJson.string(o, "state").filter("stopped"::equals).isPresent()) {
            return;
          }
          ShadowJson.strings(o, "uplinks").stream()
              .map(byAddress::get)
              .filter(Objects::nonNull)
              .distinct()
              .forEach(name ->
                  bonding
                      .computeIfAbsent(name, k -> new ArrayList<>())
                      .add(
                          UplinkBonding.builder()
                              .output(output)
                              .state(UplinkBondingState.BONDED)
                              .build()));
          ShadowJson.list(o, "standby")
              .forEach(s ->
                  ShadowJson.string(s, "interface")
                      .ifPresent(name -> {
                        UplinkBonding.Builder standby =
                            UplinkBonding.builder()
                                .output(output)
                                .state(UplinkBondingState.STANDBY);
                        ShadowJson.string(s, "shares_public_address_with")
                            .ifPresent(standby::sharesPublicAddressWith);
                        bonding.computeIfAbsent(name, k -> new ArrayList<>()).add(standby.build());
                      }));
        });
    return bonding;
  }

  private static Optional<Instant> instant(String text) {
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
