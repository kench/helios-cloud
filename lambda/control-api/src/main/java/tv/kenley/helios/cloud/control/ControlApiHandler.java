package tv.kenley.helios.cloud.control;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import tv.kenley.helios.cloud.common.LambdaEvents;

/**
 * The Lambda entry point for the Control API, behind IAM-authorized API Gateway routes. Its handler
 * string is {@code tv.kenley.helios.cloud.control.ControlApiHandler::handleRequest}. It reads
 * {@code DEVICES_TABLE} and {@code IOT_DATA_ENDPOINT} (the account's {@code iot:Data-ATS} host)
 * from the environment.
 */
public final class ControlApiHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final ControlApi api;

  /** The handler Lambda creates once per execution environment, configured from the environment. */
  public ControlApiHandler() {
    this(fromEnvironment(System.getenv()));
  }

  ControlApiHandler(ControlApi api) {
    this.api = api;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    Map<String, String> query =
        event.getQueryStringParameters() == null ? Map.of() : event.getQueryStringParameters();
    return LambdaEvents.response(api.handle(LambdaEvents.request(event, context), query));
  }

  /** The IoT data endpoint in {@code env}, as a URI. */
  static URI iotEndpoint(Map<String, String> env) {
    return URI.create("https://" + required(env, "IOT_DATA_ENDPOINT"));
  }

  private static ControlApi fromEnvironment(Map<String, String> env) {
    DynamoDbClient db =
        DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build();
    IotDataPlaneClient iot =
        IotDataPlaneClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .endpointOverride(iotEndpoint(env))
            .build();
    return new ControlApi(
        new ControlService(
            new DeviceDirectory(db, required(env, "DEVICES_TABLE")), new IotShadows(iot)),
        System.out::println,
        System.err::println,
        Clock.systemUTC());
  }

  static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be set");
    }
    return value;
  }
}
