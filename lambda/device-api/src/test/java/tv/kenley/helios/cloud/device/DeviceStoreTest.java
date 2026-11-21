package tv.kenley.helios.cloud.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

@ExtendWith(MockitoExtension.class)
final class DeviceStoreTest {
  private static final DeviceStore.Tables TABLES =
      new DeviceStore.Tables("devices", "mac_index", "challenges");
  private static final String MAC = "00:d0:93:4a:ba:ae";

  @Mock private DynamoDbClient db;
  @Captor private ArgumentCaptor<BatchGetItemRequest> batch;
  @Captor private ArgumentCaptor<UpdateItemRequest> update;

  private DeviceStore store() {
    return new DeviceStore(db, TABLES);
  }

  private static AttributeValue str(String value) {
    return AttributeValue.fromS(value);
  }

  private static Map<String, AttributeValue> macItem(String mac, String deviceId) {
    return Map.of("mac", str(mac), "deviceId", str(deviceId));
  }

  @Test
  void looksMacsUpWithConsistentBatchGet() {
    when(db.batchGetItem(batch.capture()))
        .thenReturn(
            BatchGetItemResponse.builder()
                .responses(Map.of("mac_index", List.of(macItem(MAC, "d-1"))))
                .build());

    Map<String, String> found = store().deviceIdsForMacs(List.of(MAC, "2c:cf:67:00:00:02"));

    assertEquals(Map.of(MAC, "d-1"), found);
    KeysAndAttributes keys = batch.getValue().requestItems().get("mac_index");
    assertTrue(keys.consistentRead());
    assertEquals(
        List.of(Map.of("mac", str(MAC)), Map.of("mac", str("2c:cf:67:00:00:02"))), keys.keys());
  }

  @Test
  void retriesUnprocessedKeys() {
    KeysAndAttributes left = KeysAndAttributes.builder().keys(Map.of("mac", str(MAC))).build();
    when(db.batchGetItem(any(BatchGetItemRequest.class)))
        .thenReturn(
            BatchGetItemResponse.builder().unprocessedKeys(Map.of("mac_index", left)).build())
        .thenReturn(
            BatchGetItemResponse.builder()
                .responses(Map.of("mac_index", List.of(macItem(MAC, "d-1"))))
                .build());

    assertEquals(Map.of(MAC, "d-1"), store().deviceIdsForMacs(List.of(MAC)));
    verify(db, times(2)).batchGetItem(any(BatchGetItemRequest.class));
  }

  @Test
  void failsIfKeysStayUnprocessed() {
    KeysAndAttributes left = KeysAndAttributes.builder().keys(Map.of("mac", str(MAC))).build();
    when(db.batchGetItem(any(BatchGetItemRequest.class)))
        .thenReturn(
            BatchGetItemResponse.builder().unprocessedKeys(Map.of("mac_index", left)).build());

    assertThrows(IllegalStateException.class, () -> store().deviceIdsForMacs(List.of(MAC)));
    verify(db, times(3)).batchGetItem(any(BatchGetItemRequest.class));
  }

  @Test
  void readsFullDeviceRecord() {
    ArgumentCaptor<GetItemRequest> get = ArgumentCaptor.forClass(GetItemRequest.class);
    when(db.getItem(get.capture()))
        .thenReturn(
            GetItemResponse.builder()
                .item(
                    Map.of(
                        "deviceId", str("d-1"),
                        "thingName", str("t-1"),
                        "status", str("disabled"),
                        "macs", AttributeValue.fromL(List.of(str(MAC))),
                        "pin", str("123456"),
                        "token",
                            AttributeValue.fromM(
                                Map.of(
                                    "tokenLabel", str("helios"),
                                    "keyLabel", str("device-key"),
                                    "challengeKeyLabel", str("challenge-key"))),
                        "challenge",
                            AttributeValue.fromM(
                                Map.of(
                                    "required",
                                    AttributeValue.fromBool(true),
                                    "publicKeyPem",
                                    str("PEM"))),
                        "aws",
                            AttributeValue.fromM(
                                Map.of(
                                    "region", str("us-west-2"),
                                    "iotEndpoint", str("iot"),
                                    "credentialsEndpoint", str("creds"),
                                    "roleAlias", str("alias")))))
                .build());

    DeviceRecord device = store().device("d-1").orElseThrow();

    assertEquals("devices", get.getValue().tableName());
    assertTrue(get.getValue().consistentRead());
    assertEquals("t-1", device.thingName());
    assertTrue(device.disabled());
    assertEquals(List.of(MAC), device.macs());
    assertEquals("123456", device.pin());
    assertEquals("challenge-key", device.token().getChallengeKeyLabel());
    assertTrue(device.challengeRequired());
    assertEquals(Optional.of("PEM"), device.challengePublicKeyPem());
    assertEquals("alias", device.aws().orElseThrow().getRoleAlias());
    assertFalse(device.toString().contains("123456"), device.toString());
  }

  @Test
  void readsMinimalDeviceRecord() {
    when(db.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder()
                .item(
                    Map.of(
                        "deviceId", str("d-1"),
                        "thingName", str("t-1"),
                        "status", str("active"),
                        "macs", AttributeValue.fromL(List.of(str(MAC))),
                        "pin", str("123456"),
                        "token",
                            AttributeValue.fromM(
                                Map.of(
                                    "tokenLabel", str("helios"), "keyLabel", str("device-key")))))
                .build());

    DeviceRecord device = store().device("d-1").orElseThrow();

    assertFalse(device.disabled());
    assertFalse(device.challengeRequired());
    assertTrue(device.challengePublicKeyPem().isEmpty());
    assertTrue(device.aws().isEmpty());
  }

  @Test
  void missingDeviceIsEmpty() {
    when(db.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

    assertTrue(store().device("d-1").isEmpty());
  }

  @Test
  void putsNewChallengeWithItsExpiryAsTtl() {
    ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);

    store()
        .putChallenge(
            new ChallengeRecord(
                "c-1", "d-1", List.of("b", "a"), "AA==", Instant.ofEpochSecond(1_800_000_000)));

    verify(db).putItem(put.capture());
    PutItemRequest request = put.getValue();
    assertEquals("challenges", request.tableName());
    assertEquals("attribute_not_exists(challengeId)", request.conditionExpression());
    assertEquals("1800000000", request.item().get("expiresAt").n());
    assertEquals(List.of(str("a"), str("b")), request.item().get("macs").l());
    assertFalse(request.item().get("used").bool());
  }

  @Test
  void usesChallengeOnceAndReturnsIt() {
    when(db.updateItem(update.capture()))
        .thenReturn(
            UpdateItemResponse.builder()
                .attributes(
                    Map.of(
                        "challengeId", str("c-1"),
                        "deviceId", str("d-1"),
                        "macs", AttributeValue.fromL(List.of(str(MAC))),
                        "nonce", str("AA=="),
                        "expiresAt", AttributeValue.fromN("1800000000"),
                        "used", AttributeValue.fromBool(false)))
                .build());

    ChallengeRecord challenge = store().useChallenge("c-1").orElseThrow();

    assertEquals(
        new ChallengeRecord(
            "c-1", "d-1", List.of(MAC), "AA==", Instant.ofEpochSecond(1_800_000_000)),
        challenge);
    UpdateItemRequest request = update.getValue();
    assertEquals("challenges", request.tableName());
    assertEquals("attribute_exists(challengeId) AND #used = :false", request.conditionExpression());
    assertEquals(ReturnValue.ALL_OLD, request.returnValues());
  }

  @Test
  void usedOrMissingChallengeIsEmpty() {
    when(db.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().message("used").build());

    assertTrue(store().useChallenge("c-1").isEmpty());
  }

  @Test
  void recordsTheLastLookup() {
    store()
        .recordLookup(
            "d-1",
            new LookupRecord(
                Instant.parse("2026-11-21T03:00:00Z"),
                "identity",
                "203.0.113.7",
                Optional.of("0.1.0"),
                Optional.of("PROXmobil3")));

    verify(db).updateItem(update.capture());
    UpdateItemRequest request = update.getValue();
    assertEquals("devices", request.tableName());
    assertEquals("attribute_exists(deviceId)", request.conditionExpression());
    Map<String, AttributeValue> lastSeen = request.expressionAttributeValues().get(":lastSeen").m();
    assertEquals("2026-11-21T03:00:00Z", lastSeen.get("at").s());
    assertEquals("203.0.113.7", lastSeen.get("sourceIp").s());
    assertEquals("PROXmobil3", lastSeen.get("hardwareModel").s());
  }

  @Test
  void ignoresLookupOfDeletedDevice() {
    when(db.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().message("gone").build());

    store()
        .recordLookup(
            "d-1",
            new LookupRecord(
                Instant.now(), "identity", "203.0.113.7", Optional.empty(), Optional.empty()));
  }
}
