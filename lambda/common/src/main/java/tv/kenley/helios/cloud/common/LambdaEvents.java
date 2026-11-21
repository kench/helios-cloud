package tv.kenley.helios.cloud.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Converts API Gateway REST proxy events to {@link RestJson} requests and back. */
public final class LambdaEvents {
  private LambdaEvents() {}

  /** The request in {@code event}. */
  public static RestJson.Request request(APIGatewayProxyRequestEvent event, Context context) {
    String body = event.getBody() == null ? "" : event.getBody();
    byte[] bytes =
        Boolean.TRUE.equals(event.getIsBase64Encoded())
            ? Base64.getDecoder().decode(body)
            : body.getBytes(StandardCharsets.UTF_8);
    Optional<APIGatewayProxyRequestEvent.RequestIdentity> identity =
        Optional.ofNullable(event.getRequestContext())
            .map(APIGatewayProxyRequestEvent.ProxyRequestContext::getIdentity);
    return new RestJson.Request(
        event.getHttpMethod(),
        event.getPath(),
        bytes,
        identity.map(APIGatewayProxyRequestEvent.RequestIdentity::getSourceIp).orElse("unknown"),
        context.getAwsRequestId(),
        identity.map(APIGatewayProxyRequestEvent.RequestIdentity::getUserArn).orElse(null));
  }

  /** {@code response} as a proxy response event. */
  public static APIGatewayProxyResponseEvent response(RestJson.Response response) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(response.status())
        .withHeaders(response.headers())
        .withBody(response.body())
        .withIsBase64Encoded(false);
  }
}
