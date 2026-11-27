package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class DynamoSettingsTest {
  private static final String ID = DeviceDirectoryTest.ID;

  private static Map<String, AttributeValue> item(String item, String value) {
    return Map.of(
        "deviceId", AttributeValue.fromS(ID),
        "item", AttributeValue.fromS(item),
        "value", AttributeValue.fromS(value));
  }

  @Test
  void keepsEachItemAsJsonByDeviceAndKey() {
    DynamoDbClient db = mock(DynamoDbClient.class);
    DynamoSettings settings = new DynamoSettings(db, "settings");

    settings.put(ID, "stream#pocket", Map.of("profile", "cam", "bitrate_kbps", ShadowJson.NULL));
    ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(db).putItem(put.capture());
    assertEquals("settings", put.getValue().tableName());
    assertEquals("stream#pocket", put.getValue().item().get("item").s());
    assertEquals(
        ShadowJson.parse("{\"profile\":\"cam\",\"bitrate_kbps\":null}".getBytes()),
        ShadowJson.parse(put.getValue().item().get("value").s().getBytes()));

    when(db.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder().item(item("stream#pocket", "{\"profile\":\"cam\"}")).build())
        .thenReturn(GetItemResponse.builder().build())
        .thenReturn(GetItemResponse.builder().item(item("stream#x", "not json")).build());
    assertEquals(
        "cam",
        ShadowJson.string(settings.get(ID, "stream#pocket").orElseThrow(), "profile")
            .orElseThrow());
    assertTrue(settings.get(ID, "stream#gone").isEmpty());
    assertTrue(settings.get(ID, "stream#x").isEmpty(), "unreadable: as if absent");

    when(db.query(any(QueryRequest.class)))
        .thenReturn(
            QueryResponse.builder()
                .items(List.of(item("wifi#Home", "{\"passphrase\":\"p\"}")))
                .lastEvaluatedKey(Map.of("deviceId", AttributeValue.fromS(ID)))
                .build())
        .thenReturn(
            QueryResponse.builder()
                .items(List.of(item("wifi#Cafe", "{\"passphrase\":\"q\"}")))
                .build());
    assertEquals(List.of("Home", "Cafe"), List.copyOf(settings.all(ID, "wifi#").keySet()));

    settings.delete(ID, "wifi#Home");
    ArgumentCaptor<DeleteItemRequest> delete = ArgumentCaptor.forClass(DeleteItemRequest.class);
    verify(db).deleteItem(delete.capture());
    assertEquals("wifi#Home", delete.getValue().key().get("item").s());
  }
}
