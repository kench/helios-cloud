package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tv.kenley.helios.cloud.control.model.GetDeviceUplinksOutput;
import tv.kenley.helios.cloud.control.model.Uplink;
import tv.kenley.helios.cloud.control.model.UplinkBonding;
import tv.kenley.helios.cloud.control.model.UplinkBondingState;
import tv.kenley.helios.cloud.control.model.UplinkWifi;

/** The net and stream shadows read into GetDeviceUplinks. Addresses are documentation ones. */
class UplinksShadowTest {
  private static UplinksShadow shadow(String net, String stream) {
    return new UplinksShadow(
        net == null ? null : ShadowJson.parse(StreamShadowTest.fixture(net)),
        stream == null ? null : ShadowJson.parse(StreamShadowTest.fixture(stream)));
  }

  private static Map<String, Uplink> byName(GetDeviceUplinksOutput output) {
    return output.getUplinks().stream()
        .collect(Collectors.toMap(Uplink::getInterface, Function.identity()));
  }

  @Test
  void readsEachInterface() {
    UplinksShadow shadow = shadow("net-shadow-uplinks.json", "stream-shadow-bond.json");
    assertTrue(shadow.reported());
    GetDeviceUplinksOutput output = shadow.uplinks(false);
    assertEquals(
        List.of("enP3p49s0", "enP4p65s0", "usb0", "wlP2p33s0", "wwan0"),
        output.getUplinks().stream().map(Uplink::getInterface).toList(),
        "sorted by name; a malformed entry is left out");
    assertEquals(Instant.ofEpochSecond(1795450330), output.getReportedAt());

    Map<String, Uplink> uplinks = byName(output);
    Uplink ethernet = uplinks.get("enP4p65s0");
    assertEquals("ethernet", ethernet.getKind());
    assertEquals("up", ethernet.getOperState());
    assertTrue(ethernet.isCarrier());
    assertEquals(List.of("192.168.5.31/24"), ethernet.getIpv4());
    assertEquals(100, ethernet.getDefaultRouteMetric(), "IPv4 routes only");
    assertTrue(ethernet.isSourceRouted());
    assertNull(ethernet.getWifi());

    Uplink down = uplinks.get("enP3p49s0");
    assertFalse(down.isSourceRouted());
    assertNull(down.getDefaultRouteMetric());
    assertNull(down.getPublicIpv4(), "not checked");
    assertEquals(List.of(), down.getBonding());

    assertEquals(
        UplinkWifi.builder().ssid("Bench").signalDbm(-38).frequencyMhz(5240).build(),
        uplinks.get("wlP2p33s0").getWifi());
    assertNull(uplinks.get("wwan0").isCarrier(), "not reported");
  }

  @Test
  void showsSharedPublicAddressesWithoutTheAddresses() {
    Map<String, Uplink> uplinks =
        byName(shadow("net-shadow-uplinks.json", "stream-shadow-bond.json").uplinks(false));
    assertEquals(
        List.of("wlP2p33s0"),
        uplinks.get("enP4p65s0").getPublicIpv4().getSharesPublicAddressWith());
    assertEquals(
        List.of("enP4p65s0"),
        uplinks.get("wlP2p33s0").getPublicIpv4().getSharesPublicAddressWith());
    assertEquals(List.of(), uplinks.get("wwan0").getPublicIpv4().getSharesPublicAddressWith());
    assertEquals(
        List.of(),
        uplinks.get("usb0").getPublicIpv4().getSharesPublicAddressWith(),
        "a failing check's kept address doesn't count");
    assertEquals("timeout", uplinks.get("usb0").getPublicIpv4().getError());
    assertEquals(
        Instant.parse("2026-11-23T16:13:00Z"), uplinks.get("usb0").getPublicIpv4().getFailedAt());
    assertEquals(
        Instant.parse("2026-11-23T16:12:00Z"),
        uplinks.get("enP4p65s0").getPublicIpv4().getCheckedAt());
    for (Uplink uplink : uplinks.values()) {
      if (uplink.getPublicIpv4() != null) {
        assertNull(uplink.getPublicIpv4().getAddress(), uplink.getInterface());
      }
    }
  }

  @Test
  void includesTheAddressesWhenAsked() {
    Map<String, Uplink> uplinks =
        byName(shadow("net-shadow-uplinks.json", "stream-shadow-bond.json").uplinks(true));
    assertEquals("203.0.113.7", uplinks.get("enP4p65s0").getPublicIpv4().getAddress());
    assertEquals("198.51.100.9", uplinks.get("wwan0").getPublicIpv4().getAddress());
    assertEquals(
        "203.0.113.7",
        uplinks.get("usb0").getPublicIpv4().getAddress(),
        "the last address found, alongside the error");
  }

  @Test
  void readsWhatEachRunningBondDoes() {
    Map<String, Uplink> uplinks =
        byName(shadow("net-shadow-uplinks.json", "stream-shadow-bond.json").uplinks(false));
    UplinkBonding bonded =
        UplinkBonding.builder().output("bond").state(UplinkBondingState.BONDED).build();
    assertEquals(List.of(bonded), uplinks.get("enP4p65s0").getBonding());
    assertEquals(List.of(bonded), uplinks.get("wwan0").getBonding());
    assertEquals(List.of(bonded), uplinks.get("usb0").getBonding());
    assertEquals(
        List.of(
            UplinkBonding.builder()
                .output("bond")
                .state(UplinkBondingState.STANDBY)
                .sharesPublicAddressWith("enP4p65s0")
                .build()),
        uplinks.get("wlP2p33s0").getBonding(),
        "standby in bond; the stopped output 'old' is left out");
  }

  @Test
  void worksWithoutStreamShadow() {
    Map<String, Uplink> uplinks = byName(shadow("net-shadow-uplinks.json", null).uplinks(false));
    assertEquals(5, uplinks.size());
    assertTrue(uplinks.values().stream().allMatch(u -> u.getBonding().isEmpty()));
  }

  @Test
  void notReportedWithoutInterfaces() {
    assertFalse(shadow(null, null).reported());
    assertFalse(
        new UplinksShadow(
                ShadowJson.parse("{\"state\":{\"reported\":{}}}".getBytes(StandardCharsets.UTF_8)),
                null)
            .reported());
  }
}
