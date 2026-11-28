package tv.kenley.helios.cloud.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LambdaEventsTest {
  /** A Context whose request ID is {@code request-1}; nothing else is used. */
  private static final Context CONTEXT =
      (Context)
          Proxy.newProxyInstance(
              Context.class.getClassLoader(),
              new Class<?>[] {Context.class},
              (proxy, method, args) ->
                  method.getName().equals("getAwsRequestId") ? "request-1" : null);

  @Test
  void readsTheRequestAndTheSigner() {
    APIGatewayProxyRequestEvent event =
        new APIGatewayProxyRequestEvent()
            .withHttpMethod("PUT")
            .withPath("/v1/admin/devices/d-1/status")
            .withBody("{}")
            .withRequestContext(
                new APIGatewayProxyRequestEvent.ProxyRequestContext()
                    .withIdentity(
                        new APIGatewayProxyRequestEvent.RequestIdentity()
                            .withSourceIp("203.0.113.7")
                            .withUserArn("arn:aws:sts::123456789012:assumed-role/r/s")));

    RestJson.Request request = LambdaEvents.request(event, CONTEXT);

    assertEquals("PUT", request.method());
    assertEquals("/v1/admin/devices/d-1/status", request.path());
    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), request.body());
    assertEquals("203.0.113.7", request.sourceIp());
    assertEquals("request-1", request.requestId());
    assertEquals("arn:aws:sts::123456789012:assumed-role/r/s", request.caller());
  }

  /** A request whose context has {@code authorizer}, as an HTTP API's JWT authorizer fills it. */
  private static APIGatewayProxyRequestEvent withAuthorizer(Map<String, Object> authorizer) {
    APIGatewayProxyRequestEvent.ProxyRequestContext context =
        new APIGatewayProxyRequestEvent.ProxyRequestContext()
            .withIdentity(
                new APIGatewayProxyRequestEvent.RequestIdentity().withSourceIp("203.0.113.7"));
    context.setAuthorizer(authorizer);
    return new APIGatewayProxyRequestEvent()
        .withHttpMethod("GET")
        .withPath("/v1/control/devices")
        .withRequestContext(context);
  }

  @Test
  void namesTheTokenUserAtTheirSignInProvider() {
    APIGatewayProxyRequestEvent event =
        withAuthorizer(
            Map.of(
                "claims",
                Map.of(
                    "iss", "https://issuer-cognito-idp.example/pool",
                    "sub", "cognito-sub",
                    "idp_iss", "https://id.twitch.tv/oauth2",
                    "idp_sub", "12345"),
                "scopes",
                "read"));

    RestJson.Request request = LambdaEvents.request(event, CONTEXT);
    assertEquals("https://id.twitch.tv/oauth2#12345", request.caller());
    assertEquals("203.0.113.7", request.sourceIp());
  }

  @Test
  void fallsBackToTheTokensOwnIssuerAndSubject() {
    assertEquals(
        "https://issuer.example/pool#s-1",
        LambdaEvents.request(
                withAuthorizer(
                    Map.of(
                        "claims",
                        Map.of("iss", "https://issuer.example/pool", "sub", "s-1", "idp_iss", ""))),
                CONTEXT)
            .caller());

    // No verified claims (a REST route, or a malformed authorizer block): no caller.
    for (Map<String, Object> authorizer :
        List.<Map<String, Object>>of(
            Map.of(), Map.of("claims", "nope"), Map.of("claims", Map.of("iss", "x")))) {
      assertNull(LambdaEvents.request(withAuthorizer(authorizer), CONTEXT).caller());
    }
  }

  @Test
  void decodesBase64AndToleratesMissingParts() {
    String encoded = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));

    RestJson.Request base64 =
        LambdaEvents.request(
            new APIGatewayProxyRequestEvent().withBody(encoded).withIsBase64Encoded(true), CONTEXT);

    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), base64.body());
    assertEquals("unknown", base64.sourceIp());
    assertNull(base64.caller());
    RestJson.Request empty = LambdaEvents.request(new APIGatewayProxyRequestEvent(), CONTEXT);
    assertEquals(0, empty.body().length);
  }

  @Test
  void writesTheResponse() {
    APIGatewayProxyResponseEvent event =
        LambdaEvents.response(new RestJson.Response(201, Map.of("A", "b"), "{}"));

    assertEquals(201, event.getStatusCode());
    assertEquals(Map.of("A", "b"), event.getHeaders());
    assertEquals("{}", event.getBody());
    assertFalse(event.getIsBase64Encoded());
  }
}
