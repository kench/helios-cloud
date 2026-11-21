package tv.kenley.helios.cloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tv.kenley.helios.cloud.model.AwsSettings;
import tv.kenley.helios.cloud.model.DeviceDescription;
import tv.kenley.helios.cloud.model.DeviceRole;
import tv.kenley.helios.cloud.model.DeviceStatus;
import tv.kenley.helios.cloud.model.LookupResult;
import tv.kenley.helios.cloud.model.TokenLabels;

final class DeviceItemsTest {
  static final String PIN = "839275103846";
  static final Instant NOW = Instant.parse("2026-11-21T05:00:00Z");
  static final TokenLabels TOKEN =
      TokenLabels.builder()
          .tokenLabel("helios")
          .keyLabel("device-key")
          .challengeKeyLabel("challenge-key")
          .build();

  static DeviceItems.NewDevice newDevice(Optional<String> pem, Optional<AwsSettings> aws) {
    return new DeviceItems.NewDevice(
        "d-01JD5VZ6R1H8P3S0W2Y4A6C8E0",
        "t-01JD5VY2K7M9Q4R6T8V0X2Z4B6",
        DeviceRole.SCANNER,
        List.of("00:d0:93:4a:ba:ae"),
        PIN,
        TOKEN,
        pem.isPresent(),
        pem,
        pem.map(p -> "ab".repeat(32)),
        aws,
        NOW);
  }

  @Test
  void writesTheLayoutTheDeviceApiReads() {
    Map<String, AttributeValue> item =
        DeviceItems.item(newDevice(Optional.empty(), Optional.empty()));

    assertEquals(PIN, item.get("pin").s());
    assertEquals("active", item.get("status").s());
    assertEquals("scanner", item.get("role").s());
    assertEquals("device-key", item.get("token").m().get("keyLabel").s());
    assertFalse(item.get("challenge").m().get("required").bool());
    assertEquals(List.of(AttributeValue.fromS("00:d0:93:4a:ba:ae")), item.get("macs").l());
    assertFalse(item.containsKey("aws"));
    assertEquals(NOW.toString(), item.get("createdAt").s());
  }

  @Test
  void describesRecordWithoutThePin() {
    AwsSettings aws =
        AwsSettings.builder()
            .region("us-west-2")
            .iotEndpoint("iot")
            .credentialsEndpoint("creds")
            .roleAlias("alias")
            .build();
    Map<String, AttributeValue> item =
        new HashMap<>(DeviceItems.item(newDevice(Optional.of("PEM"), Optional.of(aws))));
    item.put("status", AttributeValue.fromS("disabled"));
    item.put("disabledAt", AttributeValue.fromS(NOW.toString()));
    item.put("disabledReason", AttributeValue.fromS("lost"));
    item.put(
        "lastSeen",
        AttributeValue.fromM(
            Map.of(
                "at", AttributeValue.fromS(NOW.toString()),
                "result", AttributeValue.fromS("identity_returned"),
                "sourceIp", AttributeValue.fromS("203.0.113.7"))));

    DeviceDescription description = DeviceItems.describe(item);

    assertEquals(DeviceStatus.DISABLED, description.getStatus());
    assertEquals(NOW, description.getDisabledAt());
    assertEquals("lost", description.getDisabledReason());
    assertTrue(description.getChallenge().isRequired());
    assertEquals("PEM", description.getChallenge().getPublicKeyPem());
    assertEquals(NOW, description.getChallenge().getRegisteredAt());
    assertEquals(aws, description.getAws());
    assertEquals(LookupResult.IDENTITY_RETURNED, description.getLastSeen().getResult());
    assertEquals("challenge-key", description.getToken().getChallengeKeyLabel());
    assertFalse(description.toString().contains(PIN));
  }

  @Test
  void describesMinimalRecord() {
    Map<String, AttributeValue> item =
        new HashMap<>(DeviceItems.item(newDevice(Optional.empty(), Optional.empty())));
    item.remove("challenge");

    DeviceDescription description = DeviceItems.describe(item);

    assertFalse(description.getChallenge().isRequired());
    assertNull(description.getChallenge().getPublicKeyPem());
    assertNull(description.getAws());
    assertNull(description.getLastSeen());
  }

  @Test
  void newDevicesDontPrintThePin() {
    assertFalse(newDevice(Optional.empty(), Optional.empty()).toString().contains(PIN));
  }
}
