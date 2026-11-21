package tv.kenley.helios.cloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import tv.kenley.helios.cloud.model.DeviceStatus;

@ExtendWith(MockitoExtension.class)
final class AdminStoreTest {
  private static final AdminStore.Tables TABLES = new AdminStore.Tables("devices", "mac_index");
  private static final String ID = "d-01JD5VZ6R1H8P3S0W2Y4A6C8E0";
  private static final List<String> MACS = List.of("00:d0:93:4a:ba:ae", "00:d0:93:4a:ba:af");

  @Mock private DynamoDbClient db;
  @Captor private ArgumentCaptor<TransactWriteItemsRequest> transaction;
  @Captor private ArgumentCaptor<UpdateItemRequest> update;

  private AdminStore store() {
    return new AdminStore(db, TABLES);
  }

  private static TransactionCanceledException cancelled(String... codes) {
    return TransactionCanceledException.builder()
        .message("cancelled")
        .cancellationReasons(
            java.util.Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList())
        .build();
  }

  @Test
  void looksUpThingNamesInTheIndex() {
    ArgumentCaptor<QueryRequest> query = ArgumentCaptor.forClass(QueryRequest.class);
    when(db.query(query.capture())).thenReturn(QueryResponse.builder().count(1).build());

    assertTrue(store().thingNameTaken("t-1"));
    assertEquals(AdminStore.THING_NAME_INDEX, query.getValue().indexName());
    assertEquals("thingName = :thingName", query.getValue().keyConditionExpression());
  }

  @Test
  void createsTheRecordAndItsMacsInOneTransaction() throws Exception {
    when(db.transactWriteItems(transaction.capture()))
        .thenReturn(TransactWriteItemsResponse.builder().build());

    store().create(Map.of("deviceId", AttributeValue.fromS(ID)), MACS);

    var items = transaction.getValue().transactItems();
    assertEquals(3, items.size());
    assertEquals("devices", items.get(0).put().tableName());
    assertEquals("attribute_not_exists(deviceId)", items.get(0).put().conditionExpression());
    assertEquals("mac_index", items.get(1).put().tableName());
    assertEquals("attribute_not_exists(mac)", items.get(1).put().conditionExpression());
    assertEquals(ID, items.get(2).put().item().get("deviceId").s());
    assertEquals(MACS.get(1), items.get(2).put().item().get("mac").s());
  }

  @Test
  void reportsWhichMacsAreTaken() {
    when(db.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(cancelled("None", "None", "ConditionalCheckFailed"));

    AdminStore.MacConflict e =
        assertThrows(
            AdminStore.MacConflict.class,
            () -> store().create(Map.of("deviceId", AttributeValue.fromS(ID)), MACS));

    assertEquals(List.of(MACS.get(1)), e.macs());
  }

  @Test
  void otherCancellationsAreRethrown() {
    TransactionCanceledException conflict = cancelled("TransactionConflict", "None", "None");
    when(db.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(conflict);

    assertSame(
        conflict,
        assertThrows(
            TransactionCanceledException.class,
            () -> store().create(Map.of("deviceId", AttributeValue.fromS(ID)), MACS)));
  }

  @Test
  void getsRecordConsistently() {
    ArgumentCaptor<GetItemRequest> get = ArgumentCaptor.forClass(GetItemRequest.class);
    when(db.getItem(get.capture()))
        .thenReturn(
            GetItemResponse.builder().item(Map.of("deviceId", AttributeValue.fromS(ID))).build())
        .thenReturn(GetItemResponse.builder().build());

    assertTrue(store().get(ID).isPresent());
    assertTrue(store().get(ID).isEmpty());
    assertTrue(get.getValue().consistentRead());
  }

  @Test
  void disablingKeepsTheFirstDisabledAtAndSetsTheReason() {
    when(db.updateItem(update.capture()))
        .thenReturn(
            UpdateItemResponse.builder()
                .attributes(Map.of("deviceId", AttributeValue.fromS(ID)))
                .build());

    assertTrue(
        store()
            .setStatus(ID, DeviceStatus.DISABLED, Optional.of("lost"), DeviceItemsTest.NOW)
            .isPresent());

    UpdateItemRequest request = update.getValue();
    assertTrue(request.updateExpression().contains("disabledAt = if_not_exists(disabledAt, :now)"));
    assertTrue(request.updateExpression().contains("disabledReason = :reason"));
    assertEquals("lost", request.expressionAttributeValues().get(":reason").s());
    assertEquals("attribute_exists(deviceId)", request.conditionExpression());
    assertEquals(ReturnValue.ALL_NEW, request.returnValues());
  }

  @Test
  void disablingWithoutReasonClearsTheOldOne() {
    when(db.updateItem(update.capture())).thenReturn(UpdateItemResponse.builder().build());

    store().setStatus(ID, DeviceStatus.DISABLED, Optional.empty(), DeviceItemsTest.NOW);

    assertTrue(update.getValue().updateExpression().endsWith("REMOVE disabledReason"));
  }

  @Test
  void enablingClearsTheDisabledFields() {
    when(db.updateItem(update.capture())).thenReturn(UpdateItemResponse.builder().build());

    store().setStatus(ID, DeviceStatus.ACTIVE, Optional.of("ignored"), DeviceItemsTest.NOW);

    assertTrue(update.getValue().updateExpression().endsWith("REMOVE disabledAt, disabledReason"));
    assertFalse(update.getValue().expressionAttributeValues().containsKey(":reason"));
  }

  @Test
  void settingStatusOfMissingRecordIsEmpty() {
    when(db.updateItem(any(UpdateItemRequest.class)))
        .thenThrow(ConditionalCheckFailedException.builder().message("missing").build());

    assertTrue(
        store()
            .setStatus(ID, DeviceStatus.ACTIVE, Optional.empty(), DeviceItemsTest.NOW)
            .isEmpty());
  }

  @Test
  void deletesTheRecordAndItsMacsOnlyWhileDisabled() throws Exception {
    when(db.transactWriteItems(transaction.capture()))
        .thenReturn(TransactWriteItemsResponse.builder().build());

    store().delete(ID, MACS);

    var items = transaction.getValue().transactItems();
    assertEquals(3, items.size());
    assertEquals("#status = :disabled", items.get(0).delete().conditionExpression());
    assertEquals("deviceId = :deviceId", items.get(1).delete().conditionExpression());
    assertEquals(MACS.get(1), items.get(2).delete().key().get("mac").s());
  }

  @Test
  void deletingReenabledRecordFails() {
    when(db.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(cancelled("ConditionalCheckFailed", "None", "None"));

    assertThrows(AdminStore.NotDisabled.class, () -> store().delete(ID, MACS));
  }

  @Test
  void otherDeleteFailuresAreRethrown() {
    when(db.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(cancelled("None", "ConditionalCheckFailed", "None"));

    assertThrows(TransactionCanceledException.class, () -> store().delete(ID, MACS));
  }
}
