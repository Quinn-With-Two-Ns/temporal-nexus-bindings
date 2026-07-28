package io.github.quinn_with_two_ns.temporal.nexus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.ServiceImplInstance;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.NexusOperationHandle;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class GeneratedBindingsIntegrationTest {
  private static final String NEXUS_TASK_QUEUE = "generated-nexus-annotations";
  private static final String WORKFLOW_TASK_QUEUE = "generated-workflow-annotations";
  private static final String ENDPOINT = "generated-nexus-annotations-endpoint";

  @Test
  public void generatesCompleteServiceSplitAcrossWorkflowClasses() {
    DeploymentServiceNexusBindings generatedService = DeploymentServiceNexusBindings.create();
    ServiceImplInstance service = ServiceImplInstance.fromInstance(generatedService);

    assertEquals("DeploymentService", service.getDefinition().getName());
    assertEquals(
        Arrays.asList("cancel", "metadata", "probe", "start", "status"),
        new ArrayList<>(new TreeSet<>(service.getOperationHandlers().keySet())));
  }

  @Test
  public void explicitlyRegisteredGeneratedServiceRunsWorkflowSignalAndQueryOperations() {
    TestEnvironmentOptions environmentOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(new NexusHeaderWorkerInterceptor())
                    .build())
            .build();
    try (TestWorkflowEnvironment environment =
        TestWorkflowEnvironment.newInstance(environmentOptions)) {
      Worker nexusWorker = environment.newWorker(NEXUS_TASK_QUEUE);
      nexusWorker.registerWorkflowImplementationTypes(CallerWorkflowImpl.class);
      DeploymentServiceNexusBindings.register(nexusWorker);
      Worker workflowWorker = environment.newWorker(WORKFLOW_TASK_QUEUE);
      workflowWorker.registerWorkflowImplementationTypes(
          DeploymentWorkflowImpl.class, MetadataWorkflowImpl.class);
      environment.createNexusEndpoint(ENDPOINT, NEXUS_TASK_QUEUE);
      environment.start();

      CallerWorkflow caller =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  CallerWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("generated-binding-caller")
                      .setTaskQueue(NEXUS_TASK_QUEUE)
                      .build());

      assertEquals(
          "running:cancelled demo:true:true",
          caller.call(new StartDeploymentInput("deployment-7", "demo", WORKFLOW_TASK_QUEUE)));
    }
  }

  @Test
  public void signalAndQueryToMissingWorkflowFailWithNotFound() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker nexusWorker = environment.newWorker(NEXUS_TASK_QUEUE);
      nexusWorker.registerWorkflowImplementationTypes(MissingWorkflowCallerImpl.class);
      DeploymentServiceNexusBindings.register(nexusWorker);
      environment.createNexusEndpoint(ENDPOINT, NEXUS_TASK_QUEUE);
      environment.start();

      // The SDK already maps WorkflowNotFoundException to NOT_FOUND on its own, so asserting only
      // on the error type would pass without the handlers' own catch. The operation-scoped message
      // is what those catches actually add, so pin that instead.
      assertStartsWith(
          "NOT_FOUND:No workflow execution found for cancel: ",
          callMissingWorkflow(environment, "cancel"));
      assertStartsWith(
          "NOT_FOUND:No workflow execution found for status: ",
          callMissingWorkflow(environment, "status"));
    }
  }

  private static String callMissingWorkflow(TestWorkflowEnvironment environment, String operation) {
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            MissingWorkflowCaller.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId("missing-workflow-caller-" + operation)
                .setTaskQueue(NEXUS_TASK_QUEUE)
                .build())
        .call(operation);
  }

  private static void assertStartsWith(String expectedPrefix, String actual) {
    assertTrue(
        "expected to start with \"" + expectedPrefix + "\" but was \"" + actual + "\"",
        actual.startsWith(expectedPrefix));
  }

  @Test
  public void unreadablePropertyFailsAsNonRetryableInternalError() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker nexusWorker = environment.newWorker(NEXUS_TASK_QUEUE);
      nexusWorker.registerWorkflowImplementationTypes(UnreadablePropertyCallerImpl.class);
      DeploymentServiceNexusBindings.register(nexusWorker);
      environment.createNexusEndpoint(ENDPOINT, NEXUS_TASK_QUEUE);
      environment.start();

      UnreadablePropertyCaller caller =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  UnreadablePropertyCaller.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("unreadable-property-caller")
                      .setTaskQueue(NEXUS_TASK_QUEUE)
                      .build());

      // A getter that always throws is a handler fault, not caller input, so it must not surface as
      // BAD_REQUEST. It is also deterministic, so it must be non-retryable — leaving retry behavior
      // unspecified would retry the operation until schedule-to-close for no benefit.
      assertStartsWith(
          "INTERNAL:NON_RETRYABLE:Failed evaluating Nexus input for probe: "
              + "failed reading property \"boom\"",
          caller.call());
    }
  }

  @WorkflowInterface
  public interface UnreadablePropertyCaller {
    @WorkflowMethod
    String call();
  }

  public static class UnreadablePropertyCallerImpl implements UnreadablePropertyCaller {
    @Override
    public String call() {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(ENDPOINT)
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                      .build())
              .build();
      DeploymentService service =
          Workflow.newNexusServiceStub(DeploymentService.class, serviceOptions);
      try {
        service.probe(new ProbeInput("any-workflow"));
        return "unexpected-success";
      } catch (NexusOperationFailure e) {
        @Nullable Throwable cause = e.getCause();
        if (cause instanceof HandlerException) {
          HandlerException handler = (HandlerException) cause;
          return handler.getErrorType()
              + ":"
              + handler.getRetryBehavior()
              + ":"
              + handler.getMessage();
        }
        return "unexpected-cause: " + cause;
      }
    }
  }

  @WorkflowInterface
  public interface MissingWorkflowCaller {
    @WorkflowMethod
    String call(String operation);
  }

  public static class MissingWorkflowCallerImpl implements MissingWorkflowCaller {
    private static final String MISSING_WORKFLOW_ID = "no-such-workflow";

    @Override
    public String call(String operation) {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(ENDPOINT)
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                      .build())
              .build();
      DeploymentService service =
          Workflow.newNexusServiceStub(DeploymentService.class, serviceOptions);
      try {
        if ("cancel".equals(operation)) {
          service.cancel(new CancelDeploymentInput(MISSING_WORKFLOW_ID, "unused"));
        } else {
          service.status(new GetDeploymentStatusInput(MISSING_WORKFLOW_ID));
        }
        return "unexpected-success";
      } catch (NexusOperationFailure e) {
        @Nullable Throwable cause = e.getCause();
        if (cause instanceof HandlerException) {
          return ((HandlerException) cause).getErrorType() + ":" + cause.getMessage();
        }
        return "unexpected-cause: " + cause;
      }
    }
  }

  @Service
  public interface DeploymentService {
    @Operation
    DeploymentResult start(StartDeploymentInput input);

    @Operation
    void cancel(CancelDeploymentInput input);

    @Operation
    List<String> status(GetDeploymentStatusInput input);

    @Operation
    String metadata(String input);

    @Operation
    void probe(ProbeInput input);
  }

  @WorkflowInterface
  public interface DeploymentWorkflow {
    @WorkflowMethod(name = "DeploymentWorkflow")
    DeploymentResult run(StartDeploymentInput input);

    @SignalMethod(name = "cancelDeployment")
    void cancel(String reason);

    @SignalMethod(name = "probeDeployment")
    void probe(String value);

    @QueryMethod(name = "deploymentStatus")
    List<String> status();
  }

  @WorkflowInterface
  public interface MetadataWorkflow {
    @WorkflowMethod(name = "MetadataWorkflow")
    String run(String requestId, String routingKey);
  }

  @ServiceMapping(DeploymentService.class)
  public static class DeploymentWorkflowImpl implements DeploymentWorkflow {
    private boolean cancelled;
    private @Nullable String reason;

    @Override
    @WorkflowOperation(
        name = "start",
        workflowId = "#{id}",
        options =
            @WorkflowStartOptions(
                taskQueue = "#{taskQueue}",
                executionTimeout = "#{executionTimeout}",
                runTimeout = "PT1H",
                retryInitialInterval = "PT1S",
                retryMaximumAttempts = "#{retryMaximumAttempts}"))
    public DeploymentResult run(StartDeploymentInput input) {
      boolean optionsApplied = startOptionsApplied();
      Workflow.await(() -> cancelled);
      return new DeploymentResult(
          Objects.requireNonNull(input.getId()),
          "cancelled " + Objects.requireNonNull(input.getName()) + ":" + optionsApplied);
    }

    private boolean startOptionsApplied() {
      @Nullable RetryOptions retry = Workflow.getInfo().getRetryOptions();
      return retry != null
          && Duration.ofHours(2).equals(Workflow.getInfo().getWorkflowExecutionTimeout())
          && Duration.ofHours(1).equals(Workflow.getInfo().getWorkflowRunTimeout())
          && Duration.ofSeconds(1).equals(retry.getInitialInterval())
          && retry.getMaximumAttempts() == 3;
    }

    @Override
    @SignalOperation(name = "cancel", workflowId = "#{id}", arguments = "#{reason}")
    public void cancel(String reason) {
      this.reason = reason;
      this.cancelled = true;
    }

    @Override
    @SignalOperation(name = "probe", workflowId = "#{id}", arguments = "#{boom}")
    public void probe(String value) {}

    @Override
    @QueryOperation(name = "status", workflowId = "#{id}")
    public List<String> status() {
      return Collections.singletonList(cancelled ? Objects.requireNonNull(reason) : "running");
    }
  }

  @ServiceMapping(DeploymentService.class)
  public static class MetadataWorkflowImpl implements MetadataWorkflow {
    @Override
    @WorkflowOperation(
        name = "metadata",
        workflowId = "metadata-#{nexus.requestId}",
        options = @WorkflowStartOptions(taskQueue = "#{nexus.headers['x-task-queue']}"),
        arguments = {"#{nexus.requestId}", "#{nexus.headers['x-routing-key']}"})
    public String run(String requestId, String routingKey) {
      return Boolean.toString(
          Workflow.getInfo().getWorkflowId().equals("metadata-" + requestId)
              && routingKey.equals("generated-route"));
    }
  }

  private static final class NexusHeaderWorkerInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          super.init(
              new WorkflowOutboundCallsInterceptorBase(outboundCalls) {
                @Override
                public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
                    ExecuteNexusOperationInput<R> input) {
                  input.getHeaders().put("X-Task-Queue", WORKFLOW_TASK_QUEUE);
                  input.getHeaders().put("X-Routing-Key", "generated-route");
                  return super.executeNexusOperation(input);
                }
              });
        }
      };
    }
  }

  @WorkflowInterface
  public interface CallerWorkflow {
    @WorkflowMethod
    String call(StartDeploymentInput input);
  }

  public static class CallerWorkflowImpl implements CallerWorkflow {
    @Override
    public String call(StartDeploymentInput input) {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(ENDPOINT)
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                      .build())
              .build();
      DeploymentService service =
          Workflow.newNexusServiceStub(DeploymentService.class, serviceOptions);
      NexusOperationHandle<DeploymentResult> start =
          Workflow.startNexusOperation(service::start, input);
      start.getExecution().get();
      String id = Objects.requireNonNull(input.getId());
      String status = service.status(new GetDeploymentStatusInput(id)).get(0);
      service.cancel(new CancelDeploymentInput(id, Objects.requireNonNull(input.getName())));
      String result = Objects.requireNonNull(start.getResult().get().getStatus());
      return status + ":" + result + ":" + service.metadata("ignored");
    }
  }

  public static final class StartDeploymentInput {
    private @Nullable String id;
    private @Nullable String name;
    private @Nullable String taskQueue;
    private @Nullable String executionTimeout;
    private int retryMaximumAttempts;

    public StartDeploymentInput() {}

    StartDeploymentInput(String id, String name, String taskQueue) {
      this.id = id;
      this.name = name;
      this.taskQueue = taskQueue;
      this.executionTimeout = "PT2H";
      this.retryMaximumAttempts = 3;
    }

    public @Nullable String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public @Nullable String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public @Nullable String getTaskQueue() {
      return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
    }

    public @Nullable String getExecutionTimeout() {
      return executionTimeout;
    }

    public void setExecutionTimeout(String executionTimeout) {
      this.executionTimeout = executionTimeout;
    }

    public int getRetryMaximumAttempts() {
      return retryMaximumAttempts;
    }

    public void setRetryMaximumAttempts(int retryMaximumAttempts) {
      this.retryMaximumAttempts = retryMaximumAttempts;
    }
  }

  public static final class CancelDeploymentInput {
    private @Nullable String id;
    private @Nullable String reason;

    public CancelDeploymentInput() {}

    CancelDeploymentInput(String id, String reason) {
      this.id = id;
      this.reason = reason;
    }

    public @Nullable String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public @Nullable String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

  /**
   * Payload whose {@code boom} property exists at compile time but always fails to read at runtime.
   * {@code @JsonIgnore} keeps it off the wire so the getter only runs where the expression runtime
   * invokes it: inside the handler.
   */
  public static final class ProbeInput {
    private @Nullable String id;

    public ProbeInput() {}

    ProbeInput(String id) {
      this.id = id;
    }

    public @Nullable String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    @JsonIgnore
    public String getBoom() {
      throw new IllegalStateException("boom");
    }
  }

  public static final class GetDeploymentStatusInput {
    private @Nullable String id;

    public GetDeploymentStatusInput() {}

    GetDeploymentStatusInput(String id) {
      this.id = id;
    }

    public @Nullable String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class DeploymentResult {
    private @Nullable String id;
    private @Nullable String status;

    public DeploymentResult() {}

    DeploymentResult(String id, String status) {
      this.id = id;
      this.status = status;
    }

    public @Nullable String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public @Nullable String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }
  }
}
