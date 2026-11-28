package tv.kenley.helios.cloud.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Converts API Gateway proxy events (REST APIs, and HTTP APIs with payload format 1.0) to {@link
 * RestJson} requests and back.
 */
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
        identity
            .map(APIGatewayProxyRequestEvent.RequestIdentity::getUserArn)
            .orElseGet(() -> tokenCaller(event)));
  }

  /**
   * The caller of a request an HTTP API's JWT authorizer let through: the user at their sign-in
   * provider, {@code <idp_iss>#<idp_sub>} (the identity provider adds both claims), else the
   * token's own {@code <iss>#<sub>}. {@code null} if the request carries no verified claims.
   */
  static String tokenCaller(APIGatewayProxyRequestEvent event) {
    Map<?, ?> claims =
        Optional.ofNullable(event.getRequestContext())
            .map(APIGatewayProxyRequestEvent.ProxyRequestContext::getAuthorizer)
            .map(authorizer -> authorizer.get("claims"))
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .orElse(null);
    if (claims == null) {
      return null;
    }
    String user = pair(claims, "idp_iss", "idp_sub");
    return user != null ? user : pair(claims, "iss", "sub");
  }

  private static String pair(Map<?, ?> claims, String issuer, String subject) {
    if (claims.get(issuer) instanceof String iss
        && claims.get(subject) instanceof String sub
        && !iss.isEmpty()
        && !sub.isEmpty()) {
      return iss + "#" + sub;
    }
    return null;
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
