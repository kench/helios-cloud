package tv.kenley.helios.cloud.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tv.kenley.helios.cloud.model.AwsSettings;
import tv.kenley.helios.cloud.model.ChallengeKeyDescription;
import tv.kenley.helios.cloud.model.DeviceDescription;
import tv.kenley.helios.cloud.model.DeviceRole;
import tv.kenley.helios.cloud.model.DeviceStatus;
import tv.kenley.helios.cloud.model.LastSeen;
import tv.kenley.helios.cloud.model.LookupResult;
import tv.kenley.helios.cloud.model.TokenLabels;

/**
 * The {@code devices} item layout, shared with the device API (see {@code DeviceStore} there), and
 * its conversion to the admin API's {@link DeviceDescription}, which never includes the PIN.
 */
final class DeviceItems {
  private DeviceItems() {}

  /** A new device's record. */
  record NewDevice(
      String deviceId,
      String thingName,
      DeviceRole role,
      List<String> macs,
      String pin,
      TokenLabels token,
      boolean challengeRequired,
      Optional<String> publicKeyPem,
      Optional<String> keyFingerprint,
      Optional<AwsSettings> aws,
      Instant now) {

    @Override
    public String toString() {
      return "NewDevice[deviceId=" + deviceId + ", thingName=" + thingName + ", pin=*REDACTED*]";
    }
  }

  /** The {@code devices} item for {@code device}. */
  static Map<String, AttributeValue> item(NewDevice device) {
    Map<String, AttributeValue> token = new HashMap<>();
    token.put("tokenLabel", str(device.token().getTokenLabel()));
    token.put("keyLabel", str(device.token().getKeyLabel()));
    Optional.ofNullable(device.token().getChallengeKeyLabel())
        .ifPresent(label -> token.put("challengeKeyLabel", str(label)));
    Map<String, AttributeValue> challenge = new HashMap<>();
    challenge.put("required", AttributeValue.fromBool(device.challengeRequired()));
    device.publicKeyPem().ifPresent(pem -> challenge.put("publicKeyPem", str(pem)));
    device.keyFingerprint().ifPresent(f -> challenge.put("keyFingerprint", str(f)));
    if (device.publicKeyPem().isPresent()) {
      challenge.put("registeredAt", str(device.now().toString()));
    }
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("deviceId", str(device.deviceId()));
    item.put("thingName", str(device.thingName()));
    item.put("role", str(device.role().getValue()));
    item.put("status", str(DeviceStatus.ACTIVE.getValue()));
    item.put("macs", AttributeValue.fromL(device.macs().stream().map(DeviceItems::str).toList()));
    item.put("pin", str(device.pin()));
    item.put("token", AttributeValue.fromM(token));
    item.put("challenge", AttributeValue.fromM(challenge));
    device
        .aws()
        .ifPresent(aws ->
            item.put(
                "aws",
                AttributeValue.fromM(
                    Map.of(
                        "region", str(aws.getRegion()),
                        "iotEndpoint", str(aws.getIotEndpoint()),
                        "credentialsEndpoint", str(aws.getCredentialsEndpoint()),
                        "roleAlias", str(aws.getRoleAlias())))));
    item.put("createdAt", str(device.now().toString()));
    item.put("updatedAt", str(device.now().toString()));
    return item;
  }

  /** The admin API's view of a {@code devices} item. The PIN is left out. */
  static DeviceDescription describe(Map<String, AttributeValue> item) {
    Map<String, AttributeValue> token = item.get("token").m();
    Map<String, AttributeValue> challenge =
        item.containsKey("challenge") ? item.get("challenge").m() : Map.of();
    DeviceDescription.Builder description =
        DeviceDescription.builder()
            .deviceId(item.get("deviceId").s())
            .thingName(item.get("thingName").s())
            .role(DeviceRole.from(item.get("role").s()))
            .status(DeviceStatus.from(item.get("status").s()))
            .macs(item.get("macs").l().stream().map(AttributeValue::s).toList())
            .token(
                TokenLabels.builder()
                    .tokenLabel(token.get("tokenLabel").s())
                    .keyLabel(token.get("keyLabel").s())
                    .challengeKeyLabel(string(token, "challengeKeyLabel").orElse(null))
                    .build())
            .challenge(
                ChallengeKeyDescription.builder()
                    .required(challenge.containsKey("required") && challenge.get("required").bool())
                    .publicKeyPem(string(challenge, "publicKeyPem").orElse(null))
                    .keyFingerprint(string(challenge, "keyFingerprint").orElse(null))
                    .registeredAt(time(challenge, "registeredAt").orElse(null))
                    .build())
            .createdAt(Instant.parse(item.get("createdAt").s()))
            .updatedAt(Instant.parse(item.get("updatedAt").s()));
    time(item, "disabledAt").ifPresent(description::disabledAt);
    string(item, "disabledReason").ifPresent(description::disabledReason);
    Optional.ofNullable(item.get("aws"))
        .map(AttributeValue::m)
        .map(aws ->
            AwsSettings.builder()
                .region(aws.get("region").s())
                .iotEndpoint(aws.get("iotEndpoint").s())
                .credentialsEndpoint(aws.get("credentialsEndpoint").s())
                .roleAlias(aws.get("roleAlias").s())
                .build())
        .ifPresent(description::aws);
    Optional.ofNullable(item.get("lastSeen"))
        .map(AttributeValue::m)
        .map(seen ->
            LastSeen.builder()
                .at(Instant.parse(seen.get("at").s()))
                .result(LookupResult.from(seen.get("result").s()))
                .sourceIp(string(seen, "sourceIp").orElse(null))
                .heliosVersion(string(seen, "heliosVersion").orElse(null))
                .hardwareModel(string(seen, "hardwareModel").orElse(null))
                .build())
        .ifPresent(description::lastSeen);
    return description.build();
  }

  private static Optional<String> string(Map<String, AttributeValue> map, String name) {
    return Optional.ofNullable(map.get(name)).map(AttributeValue::s);
  }

  private static Optional<Instant> time(Map<String, AttributeValue> map, String name) {
    return string(map, name).map(Instant::parse);
  }

  static AttributeValue str(String value) {
    return AttributeValue.fromS(value);
  }
}
