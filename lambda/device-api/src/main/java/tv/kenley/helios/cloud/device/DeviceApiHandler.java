package tv.kenley.helios.cloud.device;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.security.SecureRandom;
import java.time.Clock;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import tv.kenley.helios.cloud.common.LambdaEvents;

/**
 * The Lambda entry point for the device API, behind an API Gateway REST API with Lambda proxy
 * integration. Its handler string is {@code
 * tv.kenley.helios.cloud.device.DeviceApiHandler::handleRequest}.
 *
 * <p>Audit entries go to stdout and errors to stderr, both one line each, so they land in the
 * function's CloudWatch log group (30-day retention, Bootstrap API section 7).
 */
public final class DeviceApiHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final DeviceApi api;

  /** The handler Lambda creates once per execution environment, configured from the environment. */
  public DeviceApiHandler() {
    this(fromEnvironment());
  }

  DeviceApiHandler(DeviceApi api) {
    this.api = api;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    return LambdaEvents.response(api.handle(LambdaEvents.request(event, context)));
  }

  private static DeviceApi fromEnvironment() {
    Settings settings = Settings.from(System.getenv());
    DynamoDbClient db =
        DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build();
    Clock clock = Clock.systemUTC();
    BootstrapService service =
        new BootstrapService(
            new DeviceStore(db, settings.tables()),
            settings.defaultAws(),
            clock,
            new SecureRandom(),
            System.err::println);
    return new DeviceApi(service, System.out::println, System.err::println, clock);
  }
}
