package tv.kenley.helios.cloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.helios.cloud.model.ChallengeKeyNotRegistered;
import tv.kenley.helios.cloud.model.ChallengeSettings;
import tv.kenley.helios.cloud.model.DeviceDescription;
import tv.kenley.helios.cloud.model.DeviceNotDisabled;
import tv.kenley.helios.cloud.model.DeviceNotFound;
import tv.kenley.helios.cloud.model.DeviceRole;
import tv.kenley.helios.cloud.model.DeviceStatus;
import tv.kenley.helios.cloud.model.InvalidChallengeKey;
import tv.kenley.helios.cloud.model.PutChallengeKeyInput;
import tv.kenley.helios.cloud.model.RegisterDeviceInput;
import tv.kenley.helios.cloud.model.RegistrationConflict;
import tv.kenley.helios.cloud.model.SetDevicePinInput;
import tv.kenley.helios.cloud.model.SetDeviceStatusInput;
import tv.kenley.helios.cloud.model.UpdateDeviceInput;

@ExtendWith(MockitoExtension.class)
final class AdminServiceTest {
  private static final String ETH = "00:d0:93:4a:ba:ae";
  private static final String ID = "d-01JD5VZ6R1H8P3S0W2Y4A6C8E0";

  @Mock private AdminStore store;
  @Captor private ArgumentCaptor<Map<String, AttributeValue>> item;

  private AdminService service;

  @BeforeEach
  void setUp() {
    service =
        new AdminService(
            store, Clock.fixed(DeviceItemsTest.NOW, ZoneOffset.UTC), new SecureRandom());
  }

  private static RegisterDeviceInput.Builder registration() {
    return RegisterDeviceInput.builder()
        .thingName("t-01JD5VY2K7M9Q4R6T8V0X2Z4B6")
        .role(DeviceRole.SCANNER)
        .macs(List.of(ETH, ETH))
        .pin(DeviceItemsTest.PIN)
        .token(DeviceItemsTest.TOKEN);
  }

  private static String p256Pem() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
  }

  @Test
  void registersActiveDeviceWithItsMacsOnce() throws Exception {
    DeviceDescription device = service.register(registration().build());

    verify(store).create(item.capture(), eq(List.of(ETH)));
    assertTrue(device.getDeviceId().matches("d-[0-9A-Z]{26}"), device.getDeviceId());
    assertEquals(device.getDeviceId(), item.getValue().get("deviceId").s());
    assertEquals(DeviceItemsTest.PIN, item.getValue().get("pin").s());
    assertEquals(DeviceStatus.ACTIVE, device.getStatus());
    assertEquals(List.of(ETH), device.getMacs());
    assertEquals(DeviceItemsTest.NOW, device.getCreatedAt());
  }

  @Test
  void registersChallengeKeyWithItsFingerprint() throws Exception {
    String pem = p256Pem();

    DeviceDescription device =
        service.register(
            registration()
                .challenge(ChallengeSettings.builder().required(true).publicKeyPem(pem).build())
                .build());

    assertTrue(device.getChallenge().isRequired());
    assertTrue(device.getChallenge().getKeyFingerprint().matches("[0-9a-f]{64}"));
    assertEquals(DeviceItemsTest.NOW, device.getChallenge().getRegisteredAt());
  }

  @Test
  void requiredChallengeNeedsKey() throws Exception {
    assertThrows(
        ValidationException.class,
        () ->
            service.register(
                registration()
                    .challenge(ChallengeSettings.builder().required(true).build())
                    .build()));
    verify(store, never()).create(anyMap(), anyList());
  }

  @Test
  void rejectsKeysThatAreNotP256() throws Exception {
    assertThrows(
        InvalidChallengeKey.class,
        () ->
            service.register(
                registration()
                    .challenge(ChallengeSettings.builder().publicKeyPem("not a key").build())
                    .build()));
  }

  @Test
  void takenThingNameConflicts() throws Exception {
    when(store.thingNameTaken("t-01JD5VY2K7M9Q4R6T8V0X2Z4B6")).thenReturn(true);

    RegistrationConflict e =
        assertThrows(RegistrationConflict.class, () -> service.register(registration().build()));

    assertEquals("t-01JD5VY2K7M9Q4R6T8V0X2Z4B6", e.getConflictingThingName());
    verify(store, never()).create(anyMap(), anyList());
  }

  @Test
  void takenMacsConflict() throws Exception {
    doThrow(new AdminStore.MacConflict(List.of(ETH))).when(store).create(anyMap(), anyList());

    RegistrationConflict e =
        assertThrows(RegistrationConflict.class, () -> service.register(registration().build()));

    assertEquals(List.of(ETH), e.getConflictingMacs());
  }

  private static Map<String, AttributeValue> stored(DeviceStatus status) {
    Map<String, AttributeValue> item =
        new HashMap<>(
            DeviceItems.item(DeviceItemsTest.newDevice(Optional.empty(), Optional.empty())));
    item.put("status", AttributeValue.fromS(status.getValue()));
    return item;
  }

  @Test
  void getsRecordOrNotFound() {
    when(store.get(ID))
        .thenReturn(Optional.of(stored(DeviceStatus.ACTIVE)))
        .thenReturn(Optional.empty());

    assertEquals(ID, service.get(ID).getDeviceId());
    assertThrows(DeviceNotFound.class, () -> service.get(ID));
  }

  @Test
  void setsTheStatusOrNotFound() {
    SetDeviceStatusInput input =
        SetDeviceStatusInput.builder()
            .deviceId(ID)
            .status(DeviceStatus.DISABLED)
            .reason("lost")
            .build();
    when(store.setStatus(eq(ID), eq(DeviceStatus.DISABLED), eq(Optional.of("lost")), any()))
        .thenReturn(Optional.of(stored(DeviceStatus.DISABLED)))
        .thenReturn(Optional.empty());

    assertEquals(DeviceStatus.DISABLED, service.setStatus(input).getStatus());
    assertThrows(DeviceNotFound.class, () -> service.setStatus(input));
  }

  @Test
  void deletesOnlyDisabledDevices() throws Exception {
    when(store.get(ID))
        .thenReturn(Optional.of(stored(DeviceStatus.ACTIVE)))
        .thenReturn(Optional.of(stored(DeviceStatus.DISABLED)));

    assertThrows(DeviceNotDisabled.class, () -> service.delete(ID));
    verify(store, never()).delete(any(), anyList());

    service.delete(ID);
    verify(store).delete(ID, List.of(ETH));
  }

  @Test
  void deviceReenabledDuringDeleteIsNotDisabled() throws Exception {
    when(store.get(ID)).thenReturn(Optional.of(stored(DeviceStatus.DISABLED)));
    doThrow(new AdminStore.NotDisabled()).when(store).delete(eq(ID), anyList());

    assertThrows(DeviceNotDisabled.class, () -> service.delete(ID));
  }

  @Test
  void setsThePinOrSaysTheDeviceIsMissing() {
    SetDevicePinInput input = SetDevicePinInput.builder().deviceId(ID).pin("123456").build();
    when(store.setPin(eq(ID), eq("123456"), any())).thenReturn(true);
    service.setPin(input);

    when(store.setPin(eq(ID), eq("123456"), any())).thenReturn(false);
    assertThrows(DeviceNotFound.class, () -> service.setPin(input));
  }

  @Test
  void replacesTheChallengeKeyWithItsFingerprint() throws Exception {
    String pem = p256Pem();
    Map<String, AttributeValue> updated =
        DeviceItems.item(DeviceItemsTest.newDevice(Optional.of(pem), Optional.empty()));
    when(store.setChallengeKey(eq(ID), eq(pem), any(), any())).thenReturn(Optional.of(updated));

    assertEquals(
        pem,
        service
            .putChallengeKey(PutChallengeKeyInput.builder().deviceId(ID).publicKeyPem(pem).build())
            .getPublicKeyPem());

    when(store.setChallengeKey(eq(ID), eq(pem), any(), any())).thenReturn(Optional.empty());
    assertThrows(
        DeviceNotFound.class,
        () ->
            service.putChallengeKey(
                PutChallengeKeyInput.builder().deviceId(ID).publicKeyPem(pem).build()));
    assertThrows(
        InvalidChallengeKey.class,
        () ->
            service.putChallengeKey(
                PutChallengeKeyInput.builder().deviceId(ID).publicKeyPem("not a key").build()));
  }

  private Map<String, AttributeValue> record(Optional<String> pem) {
    return DeviceItems.item(DeviceItemsTest.newDevice(pem, Optional.empty()));
  }

  @Test
  void updatesTheMembersGivenAndMovesTheMacs() throws Exception {
    Map<String, AttributeValue> current = record(Optional.empty());
    when(store.get(ID)).thenReturn(Optional.of(current));
    ArgumentCaptor<Map<String, AttributeValue>> set = ArgumentCaptor.captor();
    ArgumentCaptor<List<String>> added = ArgumentCaptor.captor();
    ArgumentCaptor<List<String>> removed = ArgumentCaptor.captor();
    String wifi = "cc:21:9d:b0:5b:16";
    String eth = "c0:74:2b:f2:75:13";

    service.update(
        UpdateDeviceInput.builder()
            .deviceId(ID)
            .macs(List.of(eth, eth, wifi))
            .role(DeviceRole.TRANSCODER)
            .build());

    List<String> before = current.get("macs").l().stream().map(AttributeValue::s).toList();
    verify(store).update(eq(ID), set.capture(), eq(before), added.capture(), removed.capture());
    assertEquals(
        List.of(eth, wifi),
        set.getValue().get("macs").l().stream().map(AttributeValue::s).toList(),
        "distinct and sorted");
    assertEquals("transcoder", set.getValue().get("role").s());
    assertTrue(set.getValue().containsKey("updatedAt"));
    assertFalse(set.getValue().containsKey("thingName"), "left out, so unchanged");
    // The record had only the scanner's Ethernet MAC: both new ones are added, it's removed.
    assertEquals(List.of(eth, wifi), added.getValue());
    assertEquals(List.of("00:d0:93:4a:ba:ae"), removed.getValue());
  }

  @Test
  void refusesConflictsAndMissingChallengeKeys() throws Exception {
    when(store.get(ID)).thenReturn(Optional.of(record(Optional.empty())));
    when(store.thingNameTaken("t-taken")).thenReturn(true);
    RegistrationConflict thing =
        assertThrows(
            RegistrationConflict.class,
            () ->
                service.update(
                    UpdateDeviceInput.builder().deviceId(ID).thingName("t-taken").build()));
    assertEquals("t-taken", thing.getConflictingThingName());
    assertThrows(
        ChallengeKeyNotRegistered.class,
        () ->
            service.update(
                UpdateDeviceInput.builder().deviceId(ID).challengeRequired(true).build()));

    doThrow(new AdminStore.MacConflict(List.of(ETH)))
        .when(store)
        .update(eq(ID), anyMap(), anyList(), anyList(), anyList());
    RegistrationConflict macs =
        assertThrows(
            RegistrationConflict.class,
            () ->
                service.update(
                    UpdateDeviceInput.builder().deviceId(ID).macs(List.of(ETH)).build()));
    assertEquals(List.of(ETH), macs.getConflictingMacs());

    doThrow(new AdminStore.Changed())
        .when(store)
        .update(eq(ID), anyMap(), anyList(), anyList(), anyList());
    assertThrows(
        RegistrationConflict.class,
        () -> service.update(UpdateDeviceInput.builder().deviceId(ID).build()));

    when(store.get("d-missing")).thenReturn(Optional.empty());
    assertThrows(
        DeviceNotFound.class,
        () -> service.update(UpdateDeviceInput.builder().deviceId("d-missing").build()));
  }

  @Test
  void setsTheOtherMembersWhenGiven() throws Exception {
    when(store.get(ID)).thenReturn(Optional.of(record(Optional.of(p256Pem()))));
    when(store.thingNameTaken("t-new")).thenReturn(false);
    ArgumentCaptor<Map<String, AttributeValue>> set = ArgumentCaptor.captor();

    service.update(
        UpdateDeviceInput.builder()
            .deviceId(ID)
            .thingName("t-new")
            .challengeRequired(true)
            .token(DeviceItemsTest.TOKEN)
            .aws(
                tv.kenley.helios.cloud.model.AwsSettings.builder()
                    .region("us-west-2")
                    .iotEndpoint("a-ats.iot.us-west-2.amazonaws.com")
                    .credentialsEndpoint("c.credentials.iot.us-west-2.amazonaws.com")
                    .roleAlias("alias")
                    .build())
            .build());

    verify(store).update(eq(ID), set.capture(), anyList(), eq(List.of()), eq(List.of()));
    assertEquals("t-new", set.getValue().get("thingName").s());
    assertTrue(set.getValue().get("challenge.required").bool());
    assertEquals("helios", set.getValue().get("token").m().get("tokenLabel").s());
    assertEquals("us-west-2", set.getValue().get("aws").m().get("region").s());
    assertFalse(set.getValue().containsKey("macs"), "macs left out, so unchanged");
  }
}
