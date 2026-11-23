package tv.kenley.helios.cloud.admin;

import static tv.kenley.helios.cloud.admin.DeviceItems.str;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import tv.kenley.helios.cloud.model.DeviceStatus;

/**
 * The admin API's DynamoDB access. Registration and deletion are transactions over the device
 * record and its MAC index entries, so a MAC can never belong to two records.
 */
final class AdminStore {
  /** The {@code devices} GSI keyed by thing name. */
  static final String THING_NAME_INDEX = "thingName";

  private final DynamoDbClient db;
  private final Tables tables;

  /**
   * The table names.
   *
   * @param devices the device records
   * @param macIndex one item per MAC, pointing to its device
   */
  record Tables(String devices, String macIndex) {}

  /** Some of the MACs are already registered. */
  static final class MacConflict extends Exception {
    private static final long serialVersionUID = 1L;
    private final List<String> macs;

    MacConflict(List<String> macs) {
      super("MACs already registered: " + macs);
      this.macs = List.copyOf(macs);
    }

    List<String> macs() {
      return macs;
    }
  }

  /** The record wasn't disabled when it was to be deleted. */
  static final class NotDisabled extends Exception {
    private static final long serialVersionUID = 1L;

    NotDisabled() {
      super("device is not disabled");
    }
  }

  AdminStore(DynamoDbClient db, Tables tables) {
    this.db = db;
    this.tables = tables;
  }

  /** Whether a record with {@code thingName} exists. */
  boolean thingNameTaken(String thingName) {
    return db.query(
                QueryRequest.builder()
                    .tableName(tables.devices())
                    .indexName(THING_NAME_INDEX)
                    .keyConditionExpression("thingName = :thingName")
                    .expressionAttributeValues(Map.of(":thingName", str(thingName)))
                    .limit(1)
                    .build())
            .count()
        > 0;
  }

  /**
   * Writes a new device record and its MAC index entries in one transaction.
   *
   * @throws MacConflict if any of the MACs already belongs to a record
   */
  void create(Map<String, AttributeValue> device, List<String> macs) throws MacConflict {
    String deviceId = device.get("deviceId").s();
    List<TransactWriteItem> items = new ArrayList<>();
    items.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(tables.devices())
                    .item(device)
                    .conditionExpression("attribute_not_exists(deviceId)")
                    .build())
            .build());
    for (String mac : macs) {
      items.add(
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(tables.macIndex())
                      .item(Map.of("mac", str(mac), "deviceId", str(deviceId)))
                      .conditionExpression("attribute_not_exists(mac)")
                      .build())
              .build());
    }
    try {
      db.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build());
    } catch (TransactionCanceledException e) {
      List<String> conflicting = new ArrayList<>();
      List<CancellationReason> reasons = e.cancellationReasons();
      for (int i = 1; i < reasons.size(); i++) {
        if ("ConditionalCheckFailed".equals(reasons.get(i).code())) {
          conflicting.add(macs.get(i - 1));
        }
      }
      if (conflicting.isEmpty()) {
        throw e;
      }
      throw new MacConflict(conflicting);
    }
  }

  /** The {@code devices} item for {@code deviceId}, if there is one. */
  Optional<Map<String, AttributeValue>> get(String deviceId) {
    Map<String, AttributeValue> item =
        db.getItem(
                GetItemRequest.builder()
                    .tableName(tables.devices())
                    .key(Map.of("deviceId", str(deviceId)))
                    .consistentRead(true)
                    .build())
            .item();
    return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
  }

  /**
   * Sets the record's status. Disabling keeps the first {@code disabledAt}; enabling clears it and
   * the reason. Returns the updated item, or empty if there is no such record.
   */
  Optional<Map<String, AttributeValue>> setStatus(
      String deviceId, DeviceStatus status, Optional<String> reason, Instant now) {
    String expression;
    Map<String, AttributeValue> values = new HashMap<>();
    values.put(":status", str(status.getValue()));
    values.put(":now", str(now.toString()));
    if (status.equals(DeviceStatus.DISABLED)) {
      expression =
          "SET #status = :status, updatedAt = :now, disabledAt = if_not_exists(disabledAt, :now)";
      if (reason.isPresent()) {
        expression += ", disabledReason = :reason";
        values.put(":reason", str(reason.get()));
      } else {
        expression += " REMOVE disabledReason";
      }
    } else {
      expression = "SET #status = :status, updatedAt = :now REMOVE disabledAt, disabledReason";
    }
    try {
      return Optional.of(
          db.updateItem(
                  UpdateItemRequest.builder()
                      .tableName(tables.devices())
                      .key(Map.of("deviceId", str(deviceId)))
                      .updateExpression(expression)
                      .conditionExpression("attribute_exists(deviceId)")
                      .expressionAttributeNames(Map.of("#status", "status"))
                      .expressionAttributeValues(values)
                      .returnValues(ReturnValue.ALL_NEW)
                      .build())
              .attributes());
    } catch (ConditionalCheckFailedException e) {
      return Optional.empty();
    }
  }

  /** The record changed between reading it and updating it, or no longer exists. */
  static final class Changed extends Exception {
    private static final long serialVersionUID = 1L;

    Changed() {
      super("the record changed meanwhile");
    }
  }

  /**
   * Updates a record in one transaction with its MAC index entries: sets {@code set} (attribute
   * name to value), adds index entries for {@code added} and removes those for {@code removed}.
   * Only if the record still has {@code expectedMacs}, so the index can't drift from the record.
   *
   * @throws MacConflict if an added MAC already belongs to a record
   * @throws Changed if the record is gone or its MACs changed meanwhile
   */
  void update(
      String deviceId,
      Map<String, AttributeValue> set,
      List<String> expectedMacs,
      List<String> added,
      List<String> removed)
      throws MacConflict, Changed {
    Map<String, String> names = new HashMap<>();
    Map<String, AttributeValue> values = new HashMap<>();
    List<String> assignments = new ArrayList<>();
    int i = 0;
    for (Map.Entry<String, AttributeValue> entry : set.entrySet()) {
      String[] path = entry.getKey().split("\\.");
      List<String> parts = new ArrayList<>();
      for (String part : path) {
        String name = "#n" + i + "_" + parts.size();
        names.put(name, part);
        parts.add(name);
      }
      String value = ":v" + i;
      values.put(value, entry.getValue());
      assignments.add(String.join(".", parts) + " = " + value);
      i++;
    }
    names.put("#macs", "macs");
    values.put(":expectedMacs", DeviceItems.macs(expectedMacs));
    List<TransactWriteItem> items = new ArrayList<>();
    items.add(
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(tables.devices())
                    .key(Map.of("deviceId", str(deviceId)))
                    .updateExpression("SET " + String.join(", ", assignments))
                    .conditionExpression("attribute_exists(deviceId) AND #macs = :expectedMacs")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .build())
            .build());
    for (String mac : added) {
      items.add(
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(tables.macIndex())
                      .item(Map.of("mac", str(mac), "deviceId", str(deviceId)))
                      .conditionExpression("attribute_not_exists(mac)")
                      .build())
              .build());
    }
    for (String mac : removed) {
      items.add(
          TransactWriteItem.builder()
              .delete(
                  Delete.builder()
                      .tableName(tables.macIndex())
                      .key(Map.of("mac", str(mac)))
                      .conditionExpression("deviceId = :deviceId")
                      .expressionAttributeValues(Map.of(":deviceId", str(deviceId)))
                      .build())
              .build());
    }
    try {
      db.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build());
    } catch (TransactionCanceledException e) {
      List<CancellationReason> reasons = e.cancellationReasons();
      if (!reasons.isEmpty() && "ConditionalCheckFailed".equals(reasons.getFirst().code())) {
        throw new Changed();
      }
      List<String> conflicting = new ArrayList<>();
      for (int j = 1; j <= added.size() && j < reasons.size(); j++) {
        if ("ConditionalCheckFailed".equals(reasons.get(j).code())) {
          conflicting.add(added.get(j - 1));
        }
      }
      if (conflicting.isEmpty()) {
        throw e;
      }
      throw new MacConflict(conflicting);
    }
  }

  /**
   * Replaces the record's PIN: the device's next bootstrap gets the new one. Returns false if there
   * is no such record.
   */
  boolean setPin(String deviceId, String pin, Instant now) {
    try {
      db.updateItem(
          UpdateItemRequest.builder()
              .tableName(tables.devices())
              .key(Map.of("deviceId", str(deviceId)))
              .updateExpression("SET pin = :pin, updatedAt = :now")
              .conditionExpression("attribute_exists(deviceId)")
              .expressionAttributeValues(Map.of(":pin", str(pin), ":now", str(now.toString())))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  /**
   * Replaces the record's challenge public key (keeping {@code challenge.required}). Returns the
   * updated item, or empty if there is no such record.
   */
  Optional<Map<String, AttributeValue>> setChallengeKey(
      String deviceId, String publicKeyPem, String fingerprint, Instant now) {
    try {
      return Optional.of(
          db.updateItem(
                  UpdateItemRequest.builder()
                      .tableName(tables.devices())
                      .key(Map.of("deviceId", str(deviceId)))
                      .updateExpression(
                          "SET challenge.publicKeyPem = :pem, challenge.keyFingerprint ="
                              + " :fingerprint, challenge.registeredAt = :now, updatedAt = :now")
                      .conditionExpression("attribute_exists(deviceId)")
                      .expressionAttributeValues(
                          Map.of(
                              ":pem",
                              str(publicKeyPem),
                              ":fingerprint",
                              str(fingerprint),
                              ":now",
                              str(now.toString())))
                      .returnValues(ReturnValue.ALL_NEW)
                      .build())
              .attributes());
    } catch (ConditionalCheckFailedException e) {
      return Optional.empty();
    }
  }

  /**
   * Deletes a disabled record and its MAC index entries in one transaction.
   *
   * @throws NotDisabled if the record isn't disabled (or was re-enabled meanwhile)
   */
  void delete(String deviceId, List<String> macs) throws NotDisabled {
    List<TransactWriteItem> items = new ArrayList<>();
    items.add(
        TransactWriteItem.builder()
            .delete(
                Delete.builder()
                    .tableName(tables.devices())
                    .key(Map.of("deviceId", str(deviceId)))
                    .conditionExpression("#status = :disabled")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(
                        Map.of(":disabled", str(DeviceStatus.DISABLED.getValue())))
                    .build())
            .build());
    for (String mac : macs) {
      items.add(
          TransactWriteItem.builder()
              .delete(
                  Delete.builder()
                      .tableName(tables.macIndex())
                      .key(Map.of("mac", str(mac)))
                      .conditionExpression("deviceId = :deviceId")
                      .expressionAttributeValues(Map.of(":deviceId", str(deviceId)))
                      .build())
              .build());
    }
    try {
      db.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build());
    } catch (TransactionCanceledException e) {
      List<CancellationReason> reasons = e.cancellationReasons();
      if (!reasons.isEmpty() && "ConditionalCheckFailed".equals(reasons.getFirst().code())) {
        throw new NotDisabled();
      }
      throw e;
    }
  }
}
