package io.github.quinn_with_two_ns.temporal.nexus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.quinn_with_two_ns.temporal.nexus.internal.NexusAnnotatedHandlerProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Test;

public class ProcessorValidationTest {
  @Test
  public void generatesTypedRegistrationApiAndOriginDocumentation() throws IOException {
    String testSource =
        source(
            "  @Service interface DeploymentService {\n"
                + "    @Operation String start(String input);\n"
                + "  }\n"
                + workflowInterface("@WorkflowMethod String run(String input);")
                + "  static class WorkflowImpl implements WorkflowContract {\n"
                + "    @Override\n"
                + "    @WorkflowOperation(service = DeploymentService.class,"
                + " name = \"start\", workflowId = \"#{input}\")\n"
                + "    public String run(String input) { return input; }\n"
                + "  }\n");

    Compilation java8Compilation = compile("GeneratedApiJava8", testSource, "8");
    assertTrue(java8Compilation.messages, java8Compilation.success);
    String java8Source =
        java8Compilation.generatedSources.get("test/DeploymentServiceNexusBindings.java");
    assertNotNull(java8Compilation.generatedSources.toString(), java8Source);
    assertTrue(java8Source, java8Source.contains("@javax.annotation.Generated("));
    assertTrue(
        java8Source, java8Source.contains("public static DeploymentServiceNexusBindings create()"));
    assertFalse(java8Source, java8Source.contains("public static Object create()"));
    assertTrue(
        java8Source,
        java8Source.contains(
            "public static DeploymentServiceNexusBindings register("
                + "io.temporal.worker.Worker worker)"));
    assertTrue(
        java8Source, java8Source.contains("worker.registerNexusServiceImplementation(binding);"));
    assertTrue(
        java8Source,
        java8Source.contains(
            "Generated Nexus binding for {@code test.TestSource.DeploymentService}"));
    assertTrue(
        java8Source,
        java8Source.contains("Nexus operation {@code test.TestSource.DeploymentService#start}"));
    assertTrue(
        java8Source,
        java8Source.contains("Temporal handler {@code test.TestSource.WorkflowImpl#run}"));

    Compilation modernCompilation = compile("GeneratedApiModern", testSource, "17");
    assertTrue(modernCompilation.messages, modernCompilation.success);
    String modernSource =
        modernCompilation.generatedSources.get("test/DeploymentServiceNexusBindings.java");
    assertNotNull(modernCompilation.generatedSources.toString(), modernSource);
    assertTrue(modernSource, modernSource.contains("@javax.annotation.processing.Generated("));
  }

  @Test
  public void acceptsMappingsSplitAcrossMultipleWorkflowImplementationClasses() throws IOException {
    Compilation compilation =
        compile(
            "SplitMappings",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "    @Operation void cancel(String input);\n"
                    + "  }\n"
                    + "  @WorkflowInterface interface StartWorkflow {\n"
                    + "    @WorkflowMethod String run(String input);\n"
                    + "  }\n"
                    + "  @WorkflowInterface interface CancelWorkflow {\n"
                    + "    @SignalMethod void cancel(String input);\n"
                    + "  }\n"
                    + "  static class StartWorkflowImpl implements StartWorkflow {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"
                    + "  static class CancelWorkflowImpl implements CancelWorkflow {\n"
                    + "    @Override\n"
                    + "    @SignalOperation(service = DeploymentService.class,"
                    + " name = \"cancel\", workflowId = \"#{input}\", arguments = \"#{input}\")\n"
                    + "    public void cancel(String input) {}\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
    assertTrue(
        compilation.generatedFiles.toString(),
        compilation.generatedFiles.contains("test/DeploymentServiceNexusBindings.java"));
  }

  @Test
  public void rejectsIncompleteServiceMappings() throws IOException {
    Compilation compilation =
        compile(
            "IncompleteMappings",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "    @Operation void cancel(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Incomplete Nexus service; missing operations: [cancel]");
  }

  @Test
  public void rejectsDuplicateOperationMappings() throws IOException {
    String implementation =
        "  static class %s implements WorkflowContract {\n"
            + "    @Override\n"
            + "    @WorkflowOperation(service = DeploymentService.class,"
            + " name = \"start\", workflowId = \"#{input}\")\n"
            + "    public String run(String input) { return input; }\n"
            + "  }\n";
    Compilation compilation =
        compile(
            "DuplicateMappings",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + String.format(implementation, "FirstWorkflow")
                    + String.format(implementation, "SecondWorkflow")));

    assertFailureContains(
        compilation, "Duplicate mapping for test.TestSource.DeploymentService.start");
  }

  @Test
  public void rejectsGeneratedClassNameCollisions() throws IOException {
    Compilation compilation =
        compile(
            "GeneratedNameCollision",
            source(
                "  static class First {\n"
                    + "    @Service interface DeploymentService {\n"
                    + "      @Operation String start(String input);\n"
                    + "    }\n"
                    + "  }\n"
                    + "  static class Second {\n"
                    + "    @Service interface DeploymentService {\n"
                    + "      @Operation String cancel(String input);\n"
                    + "    }\n"
                    + "  }\n"
                    + "  @WorkflowInterface interface StartWorkflow {\n"
                    + "    @WorkflowMethod String start(String input);\n"
                    + "  }\n"
                    + "  @WorkflowInterface interface CancelWorkflow {\n"
                    + "    @WorkflowMethod String cancel(String input);\n"
                    + "  }\n"
                    + "  static class StartWorkflowImpl implements StartWorkflow {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = First.DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String start(String input) { return input; }\n"
                    + "  }\n"
                    + "  static class CancelWorkflowImpl implements CancelWorkflow {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = Second.DeploymentService.class,"
                    + " name = \"cancel\", workflowId = \"#{input}\")\n"
                    + "    public String cancel(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Generated class test.DeploymentServiceNexusBindings for"
            + " test.TestSource.Second.DeploymentService collides with the binding generated for"
            + " test.TestSource.First.DeploymentService;"
            + " rename one service or move it to another package");
  }

  @Test
  public void rejectsUpdateMappingsUntilAsyncSupportExists() throws IOException {
    Compilation compilation =
        compile(
            "UpdateMapping",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String update(String input);\n"
                    + "  }\n"
                    + workflowInterface("@UpdateMethod String update(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @UpdateOperation(service = DeploymentService.class,"
                    + " name = \"update\", workflowId = \"#{input}\")\n"
                    + "    public String update(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Nexus update operations require asynchronous update support;"
            + " TODO for the real implementation");
  }

  @Test
  public void rejectsMalformedExpressions() throws IOException {
    Compilation compilation =
        compile(
            "MalformedExpression",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Invalid workflowId expression");
  }

  @Test
  public void validatesNestedBeanMapListAndArrayExpressions() throws IOException {
    Compilation compilation =
        compileFully(
            "TypedExpressions",
            source(
                "  static class Base<T> {\n"
                    + "    public T getInherited() { return null; }\n"
                    + "  }\n"
                    + "  static class Item {\n"
                    + "    public String getName() { return \"item\"; }\n"
                    + "  }\n"
                    + "  static class DeployInput extends Base<String> {\n"
                    + "    public String publicField;\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "    public java.util.List<Item> getItems() { return null; }\n"
                    + "    public Item[] getItemArray() { return null; }\n"
                    + "    public java.util.Map<String, Integer> getMetadata() { return null; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface(
                        "@WorkflowMethod String run(String id, String first, String arrayItem,"
                            + " int priority, String inherited, String field);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"deployment-#{id}\","
                    + " arguments = {\"#{id}\", \"#{items[0].name}\","
                    + " \"#{itemArray[0].name}\", \"#{metadata['priority']}\","
                    + " \"#{inherited}\", \"#{publicField}\"})\n"
                    + "    public String run(String id, String first, String arrayItem,"
                    + " int priority, String inherited, String field) { return id; }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
  }

  @Test
  public void validatesRecordComponentExpressions() throws IOException {
    Compilation compilation =
        compile(
            "RecordComponents",
            source(
                "  record Nested(String region) {}\n"
                    + "  record DeployInput(String id, Nested nested, int attempts) {}\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface(
                        "@WorkflowMethod String run(String id, String region, int attempts);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"deployment-#{id}\","
                    + " arguments = {\"#{id}\", \"#{nested.region}\", \"#{attempts}\"})\n"
                    + "    public String run(String id, String region, int attempts) {\n"
                    + "      return id;\n"
                    + "    }\n"
                    + "  }\n"),
            "17");

    assertTrue(compilation.messages, compilation.success);
  }

  @Test
  public void rejectsNonComponentAccessorsOnRecords() throws IOException {
    Compilation compilation =
        compile(
            "RecordNonComponentAccessor",
            source(
                "  record DeployInput(String id) {\n"
                    + "    public String derived() { return id + \"!\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\","
                    + " arguments = \"#{derived}\")\n"
                    + "    public String run(String value) { return value; }\n"
                    + "  }\n"),
            "17");

    assertFailureContains(
        compilation, "Invalid arguments[0] expression: property \"derived\" does not exist");
  }

  @Test
  public void generatedBindingsCompileForGenericArrayParameters() throws IOException {
    Compilation compilation =
        compileFully(
            "GenericArrayParameter",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(java.util.List<String>[] input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(java.util.List<String>[] v);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"generic-array\")\n"
                    + "    public String run(java.util.List<String>[] v) { return \"x\"; }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
    String generatedSource =
        compilation.generatedSources.get("test/DeploymentServiceNexusBindings.java");
    assertNotNull(compilation.generatedSources.toString(), generatedSource);
    assertTrue(generatedSource, generatedSource.contains("java.util.List[].class"));
  }

  @Test
  public void validatesNexusRequestMetadataExpressions() throws IOException {
    Compilation compilation =
        compile(
            "RequestMetadataExpressions",
            source(
                "  static class DeployInput {\n"
                    + "    public String getRequestId() { return \"payload-request\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface(
                        "@WorkflowMethod String run(String requestId, String routingKey,"
                            + " java.util.Map<String, String> headers,"
                            + " String payloadRequestId);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"deployment-#{nexus.requestId}\","
                    + " options = @WorkflowStartOptions("
                    + "taskQueue = \"#{nexus.headers['x-task-queue']}\"),"
                    + " arguments = {\"#{nexus.requestId}\","
                    + " \"#{nexus.headers['x-routing-key']}\","
                    + " \"#{nexus.headers}\", \"#{requestId}\"})\n"
                    + "    public String run(String requestId, String routingKey,"
                    + " java.util.Map<String, String> headers, String payloadRequestId) {\n"
                    + "      return requestId;\n"
                    + "    }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
  }

  @Test
  public void validatesRootListAccess() throws IOException {
    Compilation compilation =
        compile(
            "RootListExpression",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(java.util.List<String> input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"list-workflow\","
                    + " arguments = \"#{[0]}\")\n"
                    + "    public String run(String value) { return value; }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
  }

  @Test
  public void acceptsImplementationDefaultServiceAndTaskQueueExpression() throws IOException {
    Compilation compilation =
        compile(
            "ImplementationDefaultService",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "    public String getTaskQueue() { return \"deployments\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + "  @WorkflowInterface interface DeploymentWorkflow {\n"
                    + "    @WorkflowMethod String run(DeployInput input);\n"
                    + "  }\n"
                    + "  @ServiceMapping(DeploymentService.class)\n"
                    + "  static class WorkflowImpl implements DeploymentWorkflow {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(name = \"start\", workflowId = \"#{id}\","
                    + " options = @WorkflowStartOptions(taskQueue = \"#{taskQueue}\"))\n"
                    + "    public String run(DeployInput input) { return input.getId(); }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
    assertTrue(
        compilation.generatedFiles.toString(),
        compilation.generatedFiles.contains("test/DeploymentServiceNexusBindings.java"));
  }

  @Test
  public void explicitMethodServiceOverridesImplementationDefault() throws IOException {
    Compilation compilation =
        compile(
            "ExplicitServiceOverride",
            source(
                "  @Service interface DefaultService {\n"
                    + "    @Operation String unused(String input);\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + "  @WorkflowInterface interface DeploymentWorkflow {\n"
                    + "    @WorkflowMethod String run(String input);\n"
                    + "  }\n"
                    + "  @ServiceMapping(DefaultService.class)\n"
                    + "  static class WorkflowImpl implements DeploymentWorkflow {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
    assertTrue(
        compilation.generatedFiles.toString(),
        compilation.generatedFiles.contains("test/DeploymentServiceNexusBindings.java"));
  }

  @Test
  public void rejectsMappingWithoutExplicitOrImplementationService() throws IOException {
    Compilation compilation =
        compile(
            "MissingDefaultService",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "A typed Nexus service is required; specify service or add @ServiceMapping"
            + " to the Temporal implementation");
  }

  @Test
  public void rejectsInvalidTaskQueueExpression() throws IOException {
    Compilation compilation =
        compile(
            "InvalidTaskQueue",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(DeployInput input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\","
                    + " options = @WorkflowStartOptions(taskQueue = \"#{missing}\"))\n"
                    + "    public String run(DeployInput input) { return input.getId(); }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Invalid options.taskQueue expression: property \"missing\" does not exist");
  }

  @Test
  public void acceptsWorkflowStartOptionExpressions() throws IOException {
    Compilation compilation =
        compile(
            "WorkflowStartOptionExpressions",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "    public String getTimeout() { return \"PT1H\"; }\n"
                    + "    public int getAttempts() { return 3; }\n"
                    + "    public String getPolicy() { return \"REJECT_DUPLICATE\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(DeployInput input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\","
                    + " options = @WorkflowStartOptions("
                    + "executionTimeout = \"#{timeout}\","
                    + " retryMaximumAttempts = \"#{attempts}\","
                    + " workflowIdReusePolicy = \"#{policy}\","
                    + " summary = \"Deployment #{id}\"))\n"
                    + "    public String run(DeployInput input) { return input.getId(); }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
  }

  @Test
  public void rejectsInvalidLiteralWorkflowStartOption() throws IOException {
    Compilation compilation =
        compile(
            "InvalidWorkflowStartDuration",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\","
                    + " options = @WorkflowStartOptions(executionTimeout = \"tomorrow\"))\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Invalid options.executionTimeout must be an ISO-8601 duration");
  }

  @Test
  public void rejectsIncompatibleLiteralWorkflowIdPolicies() throws IOException {
    Compilation compilation =
        compile(
            "IncompatibleWorkflowIdPolicies",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\","
                    + " options = @WorkflowStartOptions("
                    + "workflowIdReusePolicy = \"TERMINATE_IF_RUNNING\","
                    + " workflowIdConflictPolicy = \"FAIL\"))\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "workflowIdConflictPolicy cannot be used with workflowIdReusePolicy"
            + " TERMINATE_IF_RUNNING");
  }

  @Test
  public void acceptsDefaultServiceOnActivityImplementation() throws IOException {
    Compilation compilation =
        compile(
            "ActivityImplementationDefault",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String reserve(String input);\n"
                    + "  }\n"
                    + "  @ActivityInterface interface DeploymentActivities {\n"
                    + "    String reserve(String input);\n"
                    + "  }\n"
                    + "  @ServiceMapping(DeploymentService.class)\n"
                    + "  static class DeploymentActivitiesImpl implements DeploymentActivities {\n"
                    + "    public String reserve(String input) { return input; }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
  }

  @Test
  public void rejectsDefaultServiceOnTemporalInterface() throws IOException {
    Compilation compilation =
        compile(
            "InvalidDefaultPlacement",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + "  @ServiceMapping(DeploymentService.class)\n"
                    + "  @WorkflowInterface interface DeploymentWorkflow {\n"
                    + "    @WorkflowMethod String run(String input);\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "@ServiceMapping requires a Temporal workflow or activity implementation class");
  }

  @Test
  public void rejectsMissingExpressionProperty() throws IOException {
    Compilation compilation =
        compile(
            "MissingExpressionProperty",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\","
                    + " arguments = \"#{missing}\")\n"
                    + "    public String run(String value) { return value; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Invalid arguments[0] expression: property \"missing\" does not exist");
  }

  @Test
  public void rejectsMissingPropertyInsideTemplateExpression() throws IOException {
    Compilation compilation =
        compile(
            "MissingTemplateProperty",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(DeployInput value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"wf-#{missing}\")\n"
                    + "    public String run(DeployInput value) { return \"x\"; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Invalid workflowId expression: property \"missing\" does not exist");
  }

  @Test
  public void rejectsNullConstantInsideTemplateExpression() throws IOException {
    Compilation compilation =
        compile(
            "NullTemplateConstant",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"wf-#{null}\")\n"
                    + "    public String run(String value) { return value; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Invalid workflowId expression: template expressions must not evaluate to null");
  }

  @Test
  public void rejectsExpressionTypeMismatch() throws IOException {
    Compilation compilation =
        compile(
            "ExpressionTypeMismatch",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(int value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\", arguments = \"#{id}\")\n"
                    + "    public String run(int value) { return String.valueOf(value); }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Invalid arguments[0] expression: expression produces java.lang.String,"
            + " which cannot be converted to int");
  }

  @Test
  public void rejectsContainerAccessOnWrongType() throws IOException {
    Compilation compilation =
        compile(
            "WrongContainerType",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\", arguments = \"#{id[0]}\")\n"
                    + "    public String run(String value) { return value; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Invalid arguments[0] expression: index access requires a List or array");
  }

  @Test
  public void rejectsStringAccessOnNonStringMapKeys() throws IOException {
    Compilation compilation =
        compile(
            "WrongMapKeyType",
            source(
                "  static class DeployInput {\n"
                    + "    public String getId() { return \"id\"; }\n"
                    + "    public java.util.Map<Integer, String> getValues() { return null; }\n"
                    + "  }\n"
                    + "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(DeployInput input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{id}\","
                    + " arguments = \"#{values['key']}\")\n"
                    + "    public String run(String value) { return value; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Invalid arguments[0] expression: quoted map keys require a String-compatible key type");
  }

  @Test
  public void rejectsActivityMappingsUntilSdkSupportExists() throws IOException {
    Compilation compilation =
        compile(
            "ActivityMapping",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String reserve(String input);\n"
                    + "  }\n"
                    + "  @ActivityInterface interface Activities {\n"
                    + "    String reserve(String input);\n"
                    + "  }\n"
                    + "  static class ActivitiesImpl implements Activities {\n"
                    + "    @Override\n"
                    + "    @ActivityOperation(service = DeploymentService.class,"
                    + " name = \"reserve\", activityId = \"#{input}\","
                    + " startToCloseTimeout = \"PT1S\")\n"
                    + "    public String reserve(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Nexus activity operations require SDK support newer than Temporal Java SDK 1.37.0;"
            + " TODO for the real implementation");
  }

  @Test
  public void discoversTemporalInterfaceInheritedThroughSuperclass() throws IOException {
    Compilation compilation =
        compile(
            "InheritedWorkflowInterface",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class BaseWorkflow implements WorkflowContract {\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"
                    + "  static class DerivedWorkflow extends BaseWorkflow {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertTrue(compilation.messages, compilation.success);
  }

  private static String source(String body) {
    return "package test;\n"
        + "import io.nexusrpc.Operation;\n"
        + "import io.nexusrpc.Service;\n"
        + "import io.temporal.activity.*;\n"
        + "import io.github.quinn_with_two_ns.temporal.nexus.*;\n"
        + "import io.temporal.workflow.*;\n"
        + "public class TestSource {\n"
        + body
        + "}\n";
  }

  private static String workflowInterface(String method) {
    return "  @WorkflowInterface interface WorkflowContract {\n    " + method + "\n  }\n";
  }

  private static Compilation compile(String name, String source) throws IOException {
    return compile(name, source, "8", true);
  }

  private static Compilation compile(String name, String source, String sourceVersion)
      throws IOException {
    return compile(name, source, sourceVersion, true);
  }

  private static Compilation compileFully(String name, String source) throws IOException {
    return compile(name, source, "8", false);
  }

  private static Compilation compile(
      String name, String source, String sourceVersion, boolean processingOnly) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull("Tests require a JDK", compiler);
    Path root = Files.createTempDirectory("nexus-annotation-processor-" + name);
    try {
      Path sourceFile = root.resolve("test/TestSource.java");
      Files.createDirectories(sourceFile.getParent());
      Files.write(sourceFile, source.getBytes(UTF_8));
      Path classes = Files.createDirectories(root.resolve("classes"));
      Path generated = Files.createDirectories(root.resolve("generated"));
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      try (StandardJavaFileManager fileManager =
          compiler.getStandardFileManager(diagnostics, Locale.ROOT, UTF_8)) {
        Iterable<? extends JavaFileObject> units =
            fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile.toFile()));
        List<String> options = new ArrayList<>();
        options.addAll(
            Arrays.asList(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                "-s",
                generated.toString(),
                "--release",
                sourceVersion));
        if (processingOnly) {
          options.add("-proc:only");
        }
        JavaCompiler.CompilationTask task =
            compiler.getTask(null, fileManager, diagnostics, options, null, units);
        task.setProcessors(Collections.singletonList(new NexusAnnotatedHandlerProcessor()));
        boolean success = task.call();
        String messages =
            diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        List<Path> generatedFilePaths;
        try (Stream<Path> paths = Files.walk(generated)) {
          generatedFilePaths =
              paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        Map<String, String> generatedSources = new LinkedHashMap<>();
        for (Path path : generatedFilePaths) {
          String relativePath = generated.relativize(path).toString().replace('\\', '/');
          generatedSources.put(relativePath, new String(Files.readAllBytes(path), UTF_8));
        }
        return new Compilation(
            success, messages, new ArrayList<>(generatedSources.keySet()), generatedSources);
      }
    } finally {
      try (Stream<Path> paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void assertFailureContains(Compilation compilation, String expected) {
    assertFalse(compilation.messages, compilation.success);
    assertTrue(compilation.messages, compilation.messages.contains(expected));
  }

  private static final class Compilation {
    private final boolean success;
    private final String messages;
    private final List<String> generatedFiles;
    private final Map<String, String> generatedSources;

    private Compilation(
        boolean success,
        String messages,
        List<String> generatedFiles,
        Map<String, String> generatedSources) {
      this.success = success;
      this.messages = messages;
      this.generatedFiles = generatedFiles;
      this.generatedSources = generatedSources;
    }
  }
}
