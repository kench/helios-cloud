package tv.kenley.helios.cloud.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import tv.kenley.helios.cloud.model.AwsSettings;
import tv.kenley.helios.cloud.model.TokenLabels;

/**
 * The device API's DynamoDB access (Bootstrap API, sections 5 and 7). The item layout, which the
 * CDK tables and the admin API share:
 *
 * <pre>
 * devices     deviceId (S, key), thingName (S), role (S), status (S: active | disabled),
 *             disabledAt (S), disabledReason (S), macs (L of S), pin (S),
 *             token (M: tokenLabel, keyLabel, challengeKeyLabel),
 *             challenge (M: required (BOOL), publicKeyPem, keyFingerprint, registeredAt),
 *             aws (M: region, iotEndpoint, credentialsEndpoint, roleAlias),
 *             lastSeen (M: time, result, sourceIp, heliosVersion, hardwareModel),
 *             createdAt (S), updatedAt (S). Timestamps are RFC 3339 strings.
 * mac_index   mac (S, key), deviceId (S)
 * challenges  challengeId (S, key), deviceId (S), macs (L of S), nonce (S, base64),
 *             expiresAt (N, epoch seconds; also the table's TTL attribute), used (BOOL)
 * </pre>
 *
 * <p>The PIN is stored as a plain string, protected by DynamoDB's encryption at rest. That's a
 * temporary simplification (Bootstrap API, section 7); section 6 asks for a dedicated KMS key.
 */
final class DeviceStore {
  /** BatchGetItem may leave keys unprocessed under load; retry this many times. */
  private static final int BATCH_ATTEMPTS = 3;

  private final DynamoDbClient db;
  private final Tables tables;

  /**
   * The table names.
   *
   * @param devices the device records
   * @param macIndex one item per MAC, pointing to its device
   * @param challenges issued challenges
   */
  record Tables(String devices, String macIndex, String challenges) {}

  DeviceStore(DynamoDbClient db, Tables tables) {
    this.db = db;
    this.tables = tables;
  }

  /** The device ID each of {@code macs} is registered to. Unregistered MACs are left out. */
  Map<String, String> deviceIdsForMacs(Collection<String> macs) {
    Map<String, String> found = new HashMap<>();
    List<Map<String, AttributeValue>> keys = new ArrayList<>();
    for (String mac : macs) {
      keys.add(Map.of("mac", str(mac)));
    }
    Map<String, KeysAndAttributes> request =
        Map.of(
            tables.macIndex(), KeysAndAttributes.builder().keys(keys).consistentRead(true).build());
    for (int attempt = 0; attempt < BATCH_ATTEMPTS && !request.isEmpty(); attempt++) {
      Map<String, KeysAndAttributes> pending = request;
      BatchGetItemResponse response =
          db.batchGetItem(BatchGetItemRequest.builder().requestItems(pending).build());
      for (Map<String, AttributeValue> item :
          response.responses().getOrDefault(tables.macIndex(), List.of())) {
        found.put(item.get("mac").s(), item.get("deviceId").s());
      }
      request = response.unprocessedKeys();
    }
    if (!request.isEmpty()) {
      throw new IllegalStateException("DynamoDB left MAC index keys unprocessed");
    }
    return found;
  }

  /** The device record with {@code deviceId}, if there is one. */
  Optional<DeviceRecord> device(String deviceId) {
    Map<String, AttributeValue> item =
        db.getItem(
                GetItemRequest.builder()
                    .tableName(tables.devices())
                    .key(Map.of("deviceId", str(deviceId)))
                    .consistentRead(true)
                    .build())
            .item();
    if (item == null || item.isEmpty()) {
      return Optional.empty();
    }
    Map<String, AttributeValue> token = item.get("token").m();
    Map<String, AttributeValue> challenge =
        item.containsKey("challenge") ? item.get("challenge").m() : Map.of();
    return Optional.of(
        new DeviceRecord(
            deviceId,
            item.get("thingName").s(),
            "disabled".equals(item.get("status").s()),
            item.get("macs").l().stream().map(AttributeValue::s).toList(),
            item.get("pin").s(),
            TokenLabels.builder()
                .tokenLabel(token.get("tokenLabel").s())
                .keyLabel(token.get("keyLabel").s())
                .challengeKeyLabel(optionalString(token, "challengeKeyLabel").orElse(null))
                .build(),
            challenge.containsKey("required") && challenge.get("required").bool(),
            optionalString(challenge, "publicKeyPem"),
            Optional.ofNullable(item.get("aws")).map(AttributeValue::m).map(DeviceStore::aws)));
  }

  /** Stores a new challenge. Challenge IDs are unique, so this never overwrites one. */
  void putChallenge(ChallengeRecord challenge) {
    db.putItem(
        PutItemRequest.builder()
            .tableName(tables.challenges())
            .item(
                Map.of(
                    "challengeId", str(challenge.challengeId()),
                    "deviceId", str(challenge.deviceId()),
                    "macs", strings(challenge.macs()),
                    "nonce", str(challenge.nonceBase64()),
                    "expiresAt", num(challenge.expiresAt().getEpochSecond()),
                    "used", AttributeValue.fromBool(false)))
            .conditionExpression("attribute_not_exists(challengeId)")
            .build());
  }

  /**
   * Marks the challenge with {@code challengeId} used and returns it, or empty if there is no such
   * challenge or it was already used. A challenge can be used once, whatever the outcome, so this
   * happens before anything is checked (Bootstrap API, section 3.2).
   */
  Optional<ChallengeRecord> useChallenge(String challengeId) {
    try {
      Map<String, AttributeValue> old =
          db.updateItem(
                  UpdateItemRequest.builder()
                      .tableName(tables.challenges())
                      .key(Map.of("challengeId", str(challengeId)))
                      .updateExpression("SET #used = :true")
                      .conditionExpression("attribute_exists(challengeId) AND #used = :false")
                      .expressionAttributeNames(Map.of("#used", "used"))
                      .expressionAttributeValues(
                          Map.of(
                              ":true", AttributeValue.fromBool(true),
                              ":false", AttributeValue.fromBool(false)))
                      .returnValues(ReturnValue.ALL_OLD)
                      .build())
              .attributes();
      return Optional.of(
          new ChallengeRecord(
              challengeId,
              old.get("deviceId").s(),
              old.get("macs").l().stream().map(AttributeValue::s).toList(),
              old.get("nonce").s(),
              Instant.ofEpochSecond(Long.parseLong(old.get("expiresAt").n()))));
    } catch (ConditionalCheckFailedException e) {
      return Optional.empty();
    }
  }

  /**
   * Records {@code lookup} as the device's {@code lastSeen}. Does nothing if the record was deleted
   * in the meantime.
   */
  void recordLookup(String deviceId, LookupRecord lookup) {
    Map<String, AttributeValue> lastSeen = new LinkedHashMap<>();
    lastSeen.put("time", str(lookup.time().toString()));
    lastSeen.put("result", str(lookup.result()));
    lastSeen.put("sourceIp", str(lookup.sourceIp()));
    lookup.heliosVersion().ifPresent(v -> lastSeen.put("heliosVersion", str(v)));
    lookup.hardwareModel().ifPresent(v -> lastSeen.put("hardwareModel", str(v)));
    try {
      db.updateItem(
          UpdateItemRequest.builder()
              .tableName(tables.devices())
              .key(Map.of("deviceId", str(deviceId)))
              .updateExpression("SET lastSeen = :lastSeen")
              .conditionExpression("attribute_exists(deviceId)")
              .expressionAttributeValues(Map.of(":lastSeen", AttributeValue.fromM(lastSeen)))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // Deleted between the lookup and now: nothing to record.
    }
  }

  private static AwsSettings aws(Map<String, AttributeValue> aws) {
    return AwsSettings.builder()
        .region(aws.get("region").s())
        .iotEndpoint(aws.get("iotEndpoint").s())
        .credentialsEndpoint(aws.get("credentialsEndpoint").s())
        .roleAlias(aws.get("roleAlias").s())
        .build();
  }

  private static Optional<String> optionalString(Map<String, AttributeValue> map, String name) {
    return Optional.ofNullable(map.get(name)).map(AttributeValue::s);
  }

  private static AttributeValue str(String value) {
    return AttributeValue.fromS(value);
  }

  private static AttributeValue num(long value) {
    return AttributeValue.fromN(Long.toString(value));
  }

  private static AttributeValue strings(List<String> values) {
    return AttributeValue.fromL(values.stream().map(AttributeValue::fromS).toList());
  }
}
