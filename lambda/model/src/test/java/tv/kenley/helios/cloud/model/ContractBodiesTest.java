package tv.kenley.helios.cloud.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.amazon.smithy.java.core.schema.SerializableShape;
import software.amazon.smithy.java.core.schema.ShapeBuilder;
import software.amazon.smithy.java.json.JsonCodec;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpPayloadTrait;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;

/**
 * Checks the generated types against the contract: every protocol-test body in the model
 * deserializes into its generated type and serializes back to the same JSON.
 */
final class ContractBodiesTest {
  private static final String SERVICES_NAMESPACE = "tv.kenley.helios.contract";
  private static final List<String> SERVICES = List.of("HeliosBootstrap", "HeliosBootstrapAdmin");

  /** The restJson1 body codec: member names as modelled, timestamps per their traits. */
  static final JsonCodec CODEC =
      JsonCodec.builder().useJsonName(true).useTimestampFormat(true).build();

  private static final Model MODEL =
      Model.assembler()
          .discoverModels(ContractBodiesTest.class.getClassLoader())
          .addImport(Path.of(System.getProperty("helios.contract.model")))
          .assemble()
          .unwrap();

  /** A protocol-test body and the shape it belongs to. */
  private record Case(String id, ShapeId shape, String body) {}

  @TestFactory
  Stream<DynamicTest> everyProtocolTestBodyRoundTrips() {
    List<Case> cases = cases();
    return cases.stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> roundTrip(c)));
  }

  @Test
  void theModelHasProtocolTests() {
    // So the factory above can't pass by finding nothing.
    assertFalse(cases().isEmpty());
  }

  @Test
  void errorsKeepTheirHttpStatus() {
    // httpError is listed in smithy-build.json's runtimeTraits; without it every client error
    // would be a 400.
    assertEquals(
        403,
        software.amazon.smithy.java.core.error.ModeledException.getHttpStatusCode(
            DeviceDisabled.$SCHEMA));
    assertEquals(
        404,
        software.amazon.smithy.java.core.error.ModeledException.getHttpStatusCode(
            UnknownDevice.$SCHEMA));
    assertEquals(
        409,
        software.amazon.smithy.java.core.error.ModeledException.getHttpStatusCode(
            AmbiguousDevice.$SCHEMA));
  }

  @Test
  void pinsAreRedactedInToString() {
    DeviceIdentity identity =
        DeviceIdentity.builder()
            .deviceId("d-01JD5VZ6R1H8P3S0W2Y4A6C8E0")
            .thingName("t-01JD5VY2K7M9Q4R6T8V0X2Z4B6")
            .pin("123456")
            .token(TokenLabels.builder().tokenLabel("helios").keyLabel("device-key").build())
            .build();

    assertFalse(identity.toString().contains("123456"), identity.toString());
  }

  private static List<Case> cases() {
    List<Case> cases = new ArrayList<>();
    TopDownIndex index = TopDownIndex.of(MODEL);
    for (String name : SERVICES) {
      ServiceShape service =
          MODEL.expectShape(ShapeId.fromParts(SERVICES_NAMESPACE, name), ServiceShape.class);
      for (OperationShape operation : index.getContainedOperations(service)) {
        operation
            .getTrait(HttpRequestTestsTrait.class)
            .ifPresent(trait -> add(cases, trait.getTestCases(), operation.getInputShape()));
        operation
            .getTrait(HttpResponseTestsTrait.class)
            .ifPresent(trait ->
                add(cases, trait.getTestCases(), payloadOrSelf(operation.getOutputShape())));
      }
      for (Shape error : MODEL.getStructureShapes()) {
        error
            .getTrait(HttpResponseTestsTrait.class)
            .filter(trait -> error.getId().getNamespace().equals(SERVICES_NAMESPACE))
            .ifPresent(trait -> add(cases, trait.getTestCases(), error.getId()));
      }
    }
    return cases.stream().distinct().toList();
  }

  private static void add(
      List<Case> cases, List<? extends HttpMessageTestCase> tests, ShapeId shape) {
    for (HttpMessageTestCase test : tests) {
      test.getBody()
          .filter(body -> !body.isBlank())
          .ifPresent(body -> cases.add(new Case(test.getId(), shape, body)));
    }
  }

  /** The `@httpPayload` member's target, which is the whole body, or the structure itself. */
  private static ShapeId payloadOrSelf(ShapeId structure) {
    Optional<MemberShape> payload =
        MODEL.expectShape(structure).members().stream()
            .filter(member -> member.hasTrait(HttpPayloadTrait.class))
            .findFirst();
    return payload.map(MemberShape::getTarget).orElse(structure);
  }

  private static void roundTrip(Case c) throws ReflectiveOperationException {
    // Generated types are named after their shapes, with a static builder().
    Class<?> type = Class.forName(Contract.class.getPackageName() + "." + c.shape().getName());
    ShapeBuilder<?> builder = (ShapeBuilder<?>) type.getMethod("builder").invoke(null);

    SerializableShape shape =
        (SerializableShape)
            CODEC.deserializeShape(c.body().getBytes(StandardCharsets.UTF_8), builder);

    assertEquals(Node.parse(c.body()), Node.parse(CODEC.serializeToString(shape)), c.id());
  }

  /** Marks the package the generated types are in. */
  private static final class Contract {}
}
