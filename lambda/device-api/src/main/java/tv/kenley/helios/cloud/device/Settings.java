package tv.kenley.helios.cloud.device;

import java.util.Map;
import java.util.Optional;
import tv.kenley.helios.cloud.model.AwsSettings;

/**
 * The Lambda's settings, from environment variables that CDK sets.
 *
 * <ul>
 *   <li>{@code DEVICES_TABLE}, {@code MAC_INDEX_TABLE}, {@code CHALLENGES_TABLE}: the tables.
 *   <li>{@code DEFAULT_IOT_ENDPOINT}, {@code DEFAULT_CREDENTIALS_ENDPOINT}, {@code
 *       DEFAULT_ROLE_ALIAS}, all or none: the AWS settings sent to devices whose record has none of
 *       its own, in the Lambda's region ({@code AWS_REGION}).
 * </ul>
 *
 * @param tables the table names
 * @param defaultAws the default AWS settings for devices, if configured
 */
record Settings(DeviceStore.Tables tables, Optional<AwsSettings> defaultAws) {

  /**
   * The settings in {@code env}.
   *
   * @throws IllegalArgumentException if a table name is missing, or the default AWS settings are
   *     only partly set
   */
  static Settings from(Map<String, String> env) {
    DeviceStore.Tables tables =
        new DeviceStore.Tables(
            required(env, "DEVICES_TABLE"),
            required(env, "MAC_INDEX_TABLE"),
            required(env, "CHALLENGES_TABLE"));
    String iot = env.get("DEFAULT_IOT_ENDPOINT");
    String credentials = env.get("DEFAULT_CREDENTIALS_ENDPOINT");
    String roleAlias = env.get("DEFAULT_ROLE_ALIAS");
    if (iot == null && credentials == null && roleAlias == null) {
      return new Settings(tables, Optional.empty());
    }
    if (iot == null || credentials == null || roleAlias == null) {
      throw new IllegalArgumentException(
          "DEFAULT_IOT_ENDPOINT, DEFAULT_CREDENTIALS_ENDPOINT and DEFAULT_ROLE_ALIAS must be set"
              + " together");
    }
    return new Settings(
        tables,
        Optional.of(
            AwsSettings.builder()
                .region(required(env, "AWS_REGION"))
                .iotEndpoint(iot)
                .credentialsEndpoint(credentials)
                .roleAlias(roleAlias)
                .build()));
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be set");
    }
    return value;
  }
}
