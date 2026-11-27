package tv.kenley.helios.cloud.control;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.smithy.java.core.serde.document.Document;

/**
 * {@link SettingsStore} in the stack's control settings table: partition key {@code deviceId}, sort
 * key {@code item}, the JSON in {@code value}. The table is encrypted at rest.
 */
final class DynamoSettings implements SettingsStore {
  private final DynamoDbClient db;
  private final String table;

  DynamoSettings(DynamoDbClient db, String table) {
    this.db = db;
    this.table = table;
  }

  @Override
  public Optional<Document> get(String deviceId, String item) {
    Map<String, AttributeValue> found =
        db.getItem(
                GetItemRequest.builder()
                    .tableName(table)
                    .key(key(deviceId, item))
                    .consistentRead(true)
                    .build())
            .item();
    return found == null || found.isEmpty() ? Optional.empty() : value(found);
  }

  @Override
  public Map<String, Document> all(String deviceId, String prefix) {
    Map<String, Document> items = new LinkedHashMap<>();
    Map<String, AttributeValue> start = null;
    do {
      QueryRequest.Builder query =
          QueryRequest.builder()
              .tableName(table)
              .keyConditionExpression("deviceId = :d AND begins_with(#item, :p)")
              .expressionAttributeNames(Map.of("#item", "item"))
              .expressionAttributeValues(
                  Map.of(":d", AttributeValue.fromS(deviceId), ":p", AttributeValue.fromS(prefix)))
              .consistentRead(true);
      if (start != null) {
        query.exclusiveStartKey(start);
      }
      QueryResponse response = db.query(query.build());
      for (Map<String, AttributeValue> found : response.items()) {
        AttributeValue item = found.get("item");
        if (item != null && item.s() != null && item.s().startsWith(prefix)) {
          value(found).ifPresent(v -> items.put(item.s().substring(prefix.length()), v));
        }
      }
      start = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
    } while (start != null && !start.isEmpty());
    return items;
  }

  @Override
  public void put(String deviceId, String item, Map<String, Object> value) {
    Map<String, AttributeValue> attributes = new LinkedHashMap<>(key(deviceId, item));
    attributes.put(
        "value", AttributeValue.fromS(new String(ShadowJson.bytes(value), StandardCharsets.UTF_8)));
    db.putItem(PutItemRequest.builder().tableName(table).item(attributes).build());
  }

  @Override
  public void delete(String deviceId, String item) {
    db.deleteItem(DeleteItemRequest.builder().tableName(table).key(key(deviceId, item)).build());
  }

  private static Map<String, AttributeValue> key(String deviceId, String item) {
    return Map.of("deviceId", AttributeValue.fromS(deviceId), "item", AttributeValue.fromS(item));
  }

  private static Optional<Document> value(Map<String, AttributeValue> found) {
    AttributeValue value = found.get("value");
    if (value == null || value.s() == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(ShadowJson.parse(value.s().getBytes(StandardCharsets.UTF_8)));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
