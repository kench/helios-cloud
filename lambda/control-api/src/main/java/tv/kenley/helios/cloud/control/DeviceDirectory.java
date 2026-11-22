package tv.kenley.helios.cloud.control;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;

/**
 * The bootstrap service's devices table, read-only: the Control API lists and looks up devices
 * there, and never sees or changes PINs (the projection leaves them out).
 */
final class DeviceDirectory {
  /** The attributes read: never the PIN. {@code status} is a reserved word. */
  private static final String PROJECTION = "deviceId, thingName, #role, #status, lastSeen";

  private static final Map<String, String> NAMES = Map.of("#role", "role", "#status", "status");

  private final DynamoDbClient db;
  private final String table;

  DeviceDirectory(DynamoDbClient db, String table) {
    this.db = db;
    this.table = table;
  }

  /** A page of devices and the token for the next one, if there is one. */
  record Page(List<DeviceSummary> devices, String nextToken) {}

  /** The device {@code deviceId}, if there's one. */
  Optional<DeviceSummary> get(String deviceId) {
    Map<String, AttributeValue> item =
        db.getItem(
                GetItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("deviceId", AttributeValue.fromS(deviceId)))
                    .projectionExpression(PROJECTION)
                    .expressionAttributeNames(NAMES)
                    .build())
            .item();
    return item == null || item.isEmpty() ? Optional.empty() : summary(item);
  }

  /**
   * Up to {@code limit} devices after {@code nextToken} (from a previous page, or {@code null}).
   *
   * @throws ValidationException if {@code nextToken} isn't one this API returned
   */
  Page list(String nextToken, int limit) {
    ScanRequest.Builder scan =
        ScanRequest.builder()
            .tableName(table)
            .limit(limit)
            .projectionExpression(PROJECTION)
            .expressionAttributeNames(NAMES);
    if (nextToken != null) {
      scan.exclusiveStartKey(Map.of("deviceId", AttributeValue.fromS(decodeToken(nextToken))));
    }
    ScanResponse response = db.scan(scan.build());
    List<DeviceSummary> devices =
        response.items().stream().map(DeviceDirectory::summary).flatMap(Optional::stream).toList();
    String next =
        response.hasLastEvaluatedKey() && response.lastEvaluatedKey().containsKey("deviceId")
            ? encodeToken(response.lastEvaluatedKey().get("deviceId").s())
            : null;
    return new Page(devices, next);
  }

  private static Optional<DeviceSummary> summary(Map<String, AttributeValue> item) {
    String deviceId = string(item, "deviceId");
    String thingName = string(item, "thingName");
    String role = string(item, "role");
    String status = string(item, "status");
    if (deviceId == null
        || thingName == null
        || DeviceRole.values().stream().noneMatch(k -> k.getValue().equals(role))
        || DeviceStatus.values().stream().noneMatch(k -> k.getValue().equals(status))) {
      return Optional.empty();
    }
    DeviceSummary.Builder summary =
        DeviceSummary.builder()
            .deviceId(deviceId)
            .thingName(thingName)
            .role(DeviceRole.from(role))
            .status(DeviceStatus.from(status));
    Optional.ofNullable(item.get("lastSeen"))
        .filter(AttributeValue::hasM)
        .map(seen -> seen.m().get("heliosVersion"))
        .map(AttributeValue::s)
        .ifPresent(summary::heliosVersion);
    return Optional.of(summary.build());
  }

  private static String string(Map<String, AttributeValue> item, String name) {
    AttributeValue value = item.get(name);
    return value == null ? null : value.s();
  }

  static String encodeToken(String deviceId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(deviceId.getBytes(StandardCharsets.UTF_8));
  }

  static String decodeToken(String token) {
    try {
      String deviceId = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      if (!deviceId.matches("^d-[0-9A-HJKMNP-TV-Z]{26}$")) {
        throw new IllegalArgumentException(deviceId);
      }
      return deviceId;
    } catch (IllegalArgumentException e) {
      throw ValidationException.builder().message("nextToken isn't from this API.").build();
    }
  }
}
