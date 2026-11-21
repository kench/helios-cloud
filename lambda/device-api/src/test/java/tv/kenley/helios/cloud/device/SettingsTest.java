package tv.kenley.helios.cloud.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SettingsTest {
  private static Map<String, String> tables() {
    Map<String, String> env = new HashMap<>();
    env.put("DEVICES_TABLE", "devices");
    env.put("MAC_INDEX_TABLE", "mac_index");
    env.put("CHALLENGES_TABLE", "challenges");
    return env;
  }

  @Test
  void readsTheTables() {
    Settings settings = Settings.from(tables());

    assertEquals(new DeviceStore.Tables("devices", "mac_index", "challenges"), settings.tables());
    assertTrue(settings.defaultAws().isEmpty());
  }

  @Test
  void readsTheDefaultAwsSettings() {
    Map<String, String> env = tables();
    env.put("AWS_REGION", "us-west-2");
    env.put("DEFAULT_IOT_ENDPOINT", "iot");
    env.put("DEFAULT_CREDENTIALS_ENDPOINT", "creds");
    env.put("DEFAULT_ROLE_ALIAS", "alias");

    Settings settings = Settings.from(env);

    assertEquals("us-west-2", settings.defaultAws().orElseThrow().getRegion());
    assertEquals("creds", settings.defaultAws().orElseThrow().getCredentialsEndpoint());
  }

  @Test
  void rejectsMissingTablesAndPartialAwsSettings() {
    Map<String, String> noTable = tables();
    noTable.put("CHALLENGES_TABLE", " ");
    Map<String, String> partial = tables();
    partial.put("DEFAULT_ROLE_ALIAS", "alias");

    assertThrows(IllegalArgumentException.class, () -> Settings.from(noTable));
    assertThrows(IllegalArgumentException.class, () -> Settings.from(partial));
  }
}
