package io.github.quinnklassen.temporal.nexusannotations;

import static org.junit.Assert.assertEquals;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.ServiceImplInstance;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
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
import java.util.TreeSet;
import org.junit.Test;

public class GeneratedBindingsIntegrationTest {
  private static final String TASK_QUEUE = "generated-nexus-annotations";
  private static final String ENDPOINT = "generated-nexus-annotations-endpoint";

  @Test
  public void generatesCompleteServiceSplitAcrossWorkflowClasses() {
    Object generatedService = DeploymentServiceNexusBindings.create();
    ServiceImplInstance service = ServiceImplInstance.fromInstance(generatedService);

    assertEquals("DeploymentService", service.getDefinition().getName());
    assertEquals(
        Arrays.asList("cancel", "start", "status"),
        new ArrayList<>(new TreeSet<>(service.getOperationHandlers().keySet())));
  }

  @Test
  public void explicitlyRegisteredGeneratedServiceRunsWorkflowSignalAndQueryOperations() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(
          CallerWorkflowImpl.class, DeploymentWorkflowImpl.class);
      worker.registerNexusServiceImplementation(DeploymentServiceNexusBindings.create());
      environment.createNexusEndpoint(ENDPOINT, TASK_QUEUE);
      environment.start();

      CallerWorkflow caller =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  CallerWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("generated-binding-caller")
                      .setTaskQueue(TASK_QUEUE)
                      .build());

      assertEquals(
          "running:cancelled demo", caller.call(new DeploymentInput("deployment-7", "demo")));
    }
  }

  @Service
  public interface DeploymentService {
    @Operation
    String start(DeploymentInput input);

    @Operation
    void cancel(DeploymentInput input);

    @Operation
    List<String> status(DeploymentInput input);
  }

  @WorkflowInterface
  public interface DeploymentWorkflow {
    @WorkflowMethod(name = "DeploymentWorkflow")
    String run(String name);

    @SignalMethod(name = "cancelDeployment")
    void cancel(String reason);

    @QueryMethod(name = "deploymentStatus")
    List<String> status();
  }

  public static class DeploymentWorkflowImpl implements DeploymentWorkflow {
    private boolean cancelled;
    private String reason;

    @Override
    @NexusWorkflowOperation(
        service = DeploymentService.class,
        name = "start",
        workflowId = "#{id}",
        arguments = "#{name}")
    public String run(String name) {
      Workflow.await(() -> cancelled);
      return "cancelled " + name;
    }

    @Override
    @NexusSignalWorkflowOperation(
        service = DeploymentService.class,
        name = "cancel",
        workflowId = "#{id}",
        arguments = "#{name}")
    public void cancel(String reason) {
      this.reason = reason;
      this.cancelled = true;
    }

    @Override
    @NexusQueryWorkflowOperation(
        service = DeploymentService.class,
        name = "status",
        workflowId = "#{id}")
    public List<String> status() {
      return Collections.singletonList(cancelled ? reason : "running");
    }
  }

  @WorkflowInterface
  public interface CallerWorkflow {
    @WorkflowMethod
    String call(DeploymentInput input);
  }

  public static class CallerWorkflowImpl implements CallerWorkflow {
    @Override
    public String call(DeploymentInput input) {
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
      NexusOperationHandle<String> start = Workflow.startNexusOperation(service::start, input);
      start.getExecution().get();
      String status = service.status(input).get(0);
      service.cancel(input);
      return status + ":" + start.getResult().get();
    }
  }

  public static final class DeploymentInput {
    private String id;
    private String name;

    public DeploymentInput() {}

    DeploymentInput(String id, String name) {
      this.id = id;
      this.name = name;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
