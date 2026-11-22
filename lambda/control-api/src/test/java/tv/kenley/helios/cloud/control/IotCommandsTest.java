package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CommandExecutionResult;
import software.amazon.awssdk.services.iot.model.GetCommandExecutionRequest;
import software.amazon.awssdk.services.iot.model.GetCommandExecutionResponse;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.StatusReason;
import software.amazon.awssdk.services.iotjobsdataplane.IotJobsDataPlaneClient;
import software.amazon.awssdk.services.iotjobsdataplane.model.StartCommandExecutionRequest;
import software.amazon.awssdk.services.iotjobsdataplane.model.StartCommandExecutionResponse;

class IotCommandsTest {
  static final String PREFIX = "arn:aws:iot:us-west-2:123456789012";

  @Test
  @SuppressWarnings("unchecked")
  void startsAnExecutionOnTheThingWithTheArgs() {
    IotJobsDataPlaneClient data = mock(IotJobsDataPlaneClient.class);
    AtomicReference<StartCommandExecutionRequest> sent = new AtomicReference<>();
    when(data.startCommandExecution(any(Consumer.class)))
        .thenAnswer(invocation -> {
          StartCommandExecutionRequest.Builder request = StartCommandExecutionRequest.builder();
          ((Consumer<StartCommandExecutionRequest.Builder>) invocation.getArgument(0))
              .accept(request);
          sent.set(request.build());
          return StartCommandExecutionResponse.builder().executionId("exec-1").build();
        });
    IotCommands commands = new IotCommands(data, mock(IotClient.class), PREFIX);

    assertEquals("exec-1", commands.start("t-1", "helios-net-wifi-set", "e30=", 10));

    StartCommandExecutionRequest request = sent.get();
    assertEquals(PREFIX + ":thing/t-1", request.targetArn());
    assertEquals(PREFIX + ":command/helios-net-wifi-set", request.commandArn());
    assertEquals(10L, request.executionTimeoutSeconds());
    assertEquals("e30=", request.parameters().get("args").s());
  }

  @SuppressWarnings("unchecked")
  private static IotCommands reading(GetCommandExecutionResponse response) {
    IotClient control = mock(IotClient.class);
    when(control.getCommandExecution(any(Consumer.class)))
        .thenAnswer(invocation -> {
          GetCommandExecutionRequest.Builder request = GetCommandExecutionRequest.builder();
          ((Consumer<GetCommandExecutionRequest.Builder>) invocation.getArgument(0))
              .accept(request);
          GetCommandExecutionRequest built = request.build();
          assertEquals(PREFIX + ":thing/t-1", built.targetArn());
          assertEquals("exec-1", built.executionId());
          assertTrue(built.includeResult());
          if (response == null) {
            throw ResourceNotFoundException.builder().message("gone").build();
          }
          return response;
        });
    return new IotCommands(mock(IotJobsDataPlaneClient.class), control, PREFIX);
  }

  @Test
  void readsTheStatusReasonAndResult() {
    Commands.Execution done =
        reading(
                GetCommandExecutionResponse.builder()
                    .status("SUCCEEDED")
                    .result(
                        Map.of(
                            "json",
                            CommandExecutionResult.builder().s("{\"removed\":true}").build()))
                    .build())
            .get("t-1", "exec-1")
            .orElseThrow();
    assertEquals("SUCCEEDED", done.status());
    assertEquals("{\"removed\":true}", done.result());
    assertTrue(done.finished());

    Commands.Execution failed =
        reading(
                GetCommandExecutionResponse.builder()
                    .status("FAILED")
                    .statusReason(
                        StatusReason.builder()
                            .reasonCode("NO_ADAPTER")
                            .reasonDescription("no Wi-Fi")
                            .build())
                    .build())
            .get("t-1", "exec-1")
            .orElseThrow();
    assertEquals("NO_ADAPTER", failed.reasonCode());
    assertEquals("no Wi-Fi", failed.reasonDescription());
    assertNull(failed.result());

    Commands.Execution waiting =
        reading(
                GetCommandExecutionResponse.builder()
                    .status("IN_PROGRESS")
                    .result(Map.of())
                    .build())
            .get("t-1", "exec-1")
            .orElseThrow();
    assertNull(waiting.reasonCode());
    assertNull(waiting.result());
    assertTrue(!waiting.finished());
    assertTrue(reading(null).get("t-1", "exec-1").isEmpty(), "unknown execution");
    for (String status : new String[] {"FAILED", "REJECTED", "TIMED_OUT"}) {
      assertTrue(new Commands.Execution(status, null, null, null).finished(), status);
    }
    assertTrue(!new Commands.Execution("CREATED", null, null, null).finished());
  }
}
