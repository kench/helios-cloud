package tv.kenley.helios.cloud.admin;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tv.kenley.helios.cloud.common.LambdaEvents;

/**
 * The Lambda entry point for the admin API, behind IAM-authorized API Gateway routes. Its handler
 * string is {@code tv.kenley.helios.cloud.admin.AdminApiHandler::handleRequest}. It reads {@code
 * DEVICES_TABLE} and {@code MAC_INDEX_TABLE} from the environment.
 */
public final class AdminApiHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final AdminApi api;

  /** The handler Lambda creates once per execution environment, configured from the environment. */
  public AdminApiHandler() {
    this(fromEnvironment(System.getenv()));
  }

  AdminApiHandler(AdminApi api) {
    this.api = api;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    return LambdaEvents.response(api.handle(LambdaEvents.request(event, context)));
  }

  /** The tables in {@code env}. */
  static AdminStore.Tables tables(Map<String, String> env) {
    return new AdminStore.Tables(required(env, "DEVICES_TABLE"), required(env, "MAC_INDEX_TABLE"));
  }

  private static AdminApi fromEnvironment(Map<String, String> env) {
    AdminStore.Tables tables = tables(env);
    DynamoDbClient db =
        DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build();
    Clock clock = Clock.systemUTC();
    return new AdminApi(
        new AdminService(new AdminStore(db, tables), clock, new SecureRandom()),
        System.out::println,
        System.err::println,
        clock);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be set");
    }
    return value;
  }
}
