package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceSummary;

class DeviceDirectoryTest {
  static final String ID = "d-01M80KB9C9T0ZSPC64NZVY1QRA";
  static final String THING = "t-01M80KAX049HNYQ4K9NYP8SNFH";

  static Map<String, AttributeValue> item(String id, String role) {
    return Map.of(
        "deviceId", AttributeValue.fromS(id),
        "thingName", AttributeValue.fromS(THING),
        "role", AttributeValue.fromS(role),
        "status", AttributeValue.fromS("active"),
        "lastSeen", AttributeValue.fromM(Map.of("heliosVersion", AttributeValue.fromS("0.3.0"))));
  }

  @Test
  void getsDeviceWithoutItsPin() {
    DynamoDbClient db = mock(DynamoDbClient.class);
    when(db.getItem(any(GetItemRequest.class)))
        .thenReturn(GetItemResponse.builder().item(item(ID, "transcoder")).build());
    DeviceSummary device = new DeviceDirectory(db, "devices").get(ID).orElseThrow();
    assertEquals(THING, device.getThingName());
    assertEquals(DeviceRole.TRANSCODER, device.getRole());
    assertEquals("0.3.0", device.getHeliosVersion());
    ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
    verify(db).getItem(request.capture());
    assertEquals("devices", request.getValue().tableName());
    assertFalse(request.getValue().projectionExpression().contains("pin"));
  }

  @Test
  void missingOrUnreadableDeviceIsNone() {
    DynamoDbClient db = mock(DynamoDbClient.class);
    when(db.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
    assertTrue(new DeviceDirectory(db, "devices").get(ID).isEmpty());
    when(db.getItem(any(GetItemRequest.class)))
        .thenReturn(GetItemResponse.builder().item(item(ID, "spaceship")).build());
    assertTrue(new DeviceDirectory(db, "devices").get(ID).isEmpty());
  }

  @Test
  void listsPagesWithOpaqueTokens() {
    DynamoDbClient db = mock(DynamoDbClient.class);
    when(db.scan(any(ScanRequest.class)))
        .thenReturn(
            ScanResponse.builder()
                .items(item(ID, "relay"), Map.of("deviceId", AttributeValue.fromS("broken")))
                .lastEvaluatedKey(Map.of("deviceId", AttributeValue.fromS(ID)))
                .build())
        .thenReturn(ScanResponse.builder().items(item(ID, "scanner")).build());
    DeviceDirectory directory = new DeviceDirectory(db, "devices");
    DeviceDirectory.Page first = directory.list(null, 10);
    assertEquals(1, first.devices().size(), "the unreadable item is skipped");
    assertEquals(DeviceDirectory.encodeToken(ID), first.nextToken());
    DeviceDirectory.Page second = directory.list(first.nextToken(), 10);
    assertNull(second.nextToken());
    ArgumentCaptor<ScanRequest> scan = ArgumentCaptor.forClass(ScanRequest.class);
    verify(db, org.mockito.Mockito.times(2)).scan(scan.capture());
    assertEquals(10, scan.getAllValues().get(0).limit());
    assertEquals(ID, scan.getAllValues().get(1).exclusiveStartKey().get("deviceId").s());
  }

  @Test
  void refusesTokensItDidntMake() {
    assertEquals(ID, DeviceDirectory.decodeToken(DeviceDirectory.encodeToken(ID)));
    assertThrows(ValidationException.class, () -> DeviceDirectory.decodeToken("%%%"));
    assertThrows(
        ValidationException.class,
        () -> DeviceDirectory.decodeToken(DeviceDirectory.encodeToken("d-short")));
  }
}
