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
        compilation,
        "Duplicate mapping for test.TestSource.DeploymentService.start; also mapped by"
            + " test.TestSource.FirstWorkflow#run");
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
  public void rejectsOutOfRangeNumericLiterals() throws IOException {
    Compilation compilation =
        compile(
            "OutOfRangeNumericLiteral",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(int value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\","
                    + " arguments = \"#{9999999999}\")\n"
                    + "    public String run(int value) { return String.valueOf(value); }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Invalid arguments[0] expression: expression \"#{9999999999}\" cannot be converted"
            + " losslessly to java.lang.Integer");
  }

  @Test
  public void rejectsMultiCharacterConstantsForCharTargets() throws IOException {
    Compilation compilation =
        compile(
            "MultiCharacterConstant",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(char value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\","
                    + " arguments = \"#{'ab'}\")\n"
                    + "    public String run(char value) { return String.valueOf(value); }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Invalid arguments[0] expression: expression \"#{'ab'}\" produced java.lang.String,"
            + " which cannot be converted to java.lang.Character");
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
  public void rejectsNonPublicHandlerMethods() throws IOException {
    Compilation compilation =
        compile(
            "NonPublicHandler",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    public String run(String input) { return input; }\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    String helper(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Annotated Temporal handler methods must be public");
  }

  @Test
  public void rejectsAnnotationsOnUndiscoverableHandlerMethods() throws IOException {
    Compilation compilation =
        compile(
            "UndiscoverableHandler",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    public String run(String input) { return input; }\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String helper(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Nexus annotation is not on a discoverable Temporal handler method");
  }

  @Test
  public void rejectsAnnotationKindMismatchingTemporalHandlerKind() throws IOException {
    Compilation compilation =
        compile(
            "HandlerKindMismatch",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation void start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod void run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @SignalOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public void run(String input) {}\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Nexus annotation kind SIGNAL does not match Temporal handler kind WORKFLOW");
  }

  @Test
  public void rejectsUnknownOperationNames() throws IOException {
    Compilation compilation =
        compile(
            "UnknownOperationName",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"missing\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Nexus service test.TestSource.DeploymentService has no operation named missing");
  }

  @Test
  public void rejectsArgumentCountMismatch() throws IOException {
    Compilation compilation =
        compile(
            "ArgumentCountMismatch",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String first, String second);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\", arguments = \"#{input}\")\n"
                    + "    public String run(String first, String second) { return first; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Argument expression count 1 does not match Temporal parameter count 2");
  }

  @Test
  public void rejectsIncompatibleDirectInput() throws IOException {
    Compilation compilation =
        compile(
            "IncompatibleDirectInput",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(java.util.List<String> value);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"start\")\n"
                    + "    public String run(java.util.List<String> value) { return \"x\"; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Nexus input java.lang.String is not assignable to Temporal argument"
            + " java.util.List<java.lang.String>");
  }

  @Test
  public void rejectsMismatchedOutputTypes() throws IOException {
    Compilation compilation =
        compile(
            "MismatchedOutput",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation Integer start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "Temporal output java.lang.String does not match Nexus output java.lang.Integer");
  }

  @Test
  public void rejectsSignalOperationsWithNonVoidOutput() throws IOException {
    Compilation compilation =
        compile(
            "SignalNonVoidOutput",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String cancel(String input);\n"
                    + "  }\n"
                    + workflowInterface("@SignalMethod String cancel(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @SignalOperation(service = DeploymentService.class,"
                    + " name = \"cancel\", workflowId = \"#{input}\")\n"
                    + "    public String cancel(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Signal Nexus operation output must be void");
  }

  @Test
  public void rejectsDuplicateNexusOperationNames() throws IOException {
    Compilation compilation =
        compile(
            "DuplicateOperationNames",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation(name = \"dup\") String start(String input);\n"
                    + "    @Operation(name = \"dup\") String cancel(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"dup\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Duplicate Nexus operation name dup");
  }

  @Test
  public void rejectsOverloadedNexusServiceMethods() throws IOException {
    Compilation compilation =
        compile(
            "OverloadedServiceMethods",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation(name = \"first\") String start(String input);\n"
                    + "    @Operation(name = \"second\") String start(Integer input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"first\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "Overloaded Nexus service methods cannot generate @OperationImpl methods");
  }

  @Test
  public void rejectsOperationsWithMultipleParameters() throws IOException {
    Compilation compilation =
        compile(
            "MultiParameterOperation",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation String start(String first, String second);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Nexus operations may have at most one input parameter");
  }

  @Test
  public void rejectsServicesWithoutOperations() throws IOException {
    Compilation compilation =
        compile(
            "EmptyService",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Typed Nexus service has no @Operation methods");
  }

  @Test
  public void rejectsMethodServiceNotAnnotatedWithService() throws IOException {
    Compilation compilation =
        compile(
            "MethodServiceNotAService",
            source(
                "  interface NotAService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = NotAService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation, "test.TestSource.NotAService is not annotated with @Service");
  }

  @Test
  public void rejectsServiceMappingValueNotAnnotatedWithService() throws IOException {
    Compilation compilation =
        compile(
            "MappingValueNotAService",
            source(
                "  interface NotAService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  @ServiceMapping(NotAService.class)\n"
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "test.TestSource.NotAService in @ServiceMapping is not annotated with @Service");
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

  @Test
  public void generatesFragmentOnlyServiceBindingRequiringTheFragmentInstance() throws IOException {
    Compilation compilation =
        compileFully(
            "FragmentOnlyService",
            source(
                deploymentService("    @Operation void cancel(String input);\n")
                    + fragment(
                        "DeploymentFragment",
                        "    private final String prefix;\n"
                            + "    public DeploymentFragment(String prefix) {"
                            + " this.prefix = prefix; }\n"
                            + handler("OperationHandler<String, String>", "start", "prefix + input")
                            + handler("OperationHandler<String, Void>", "cancel", "null"))));

    assertTrue(compilation.messages, compilation.success);
    String generated = generatedBinding(compilation);
    assertTrue(
        generated,
        generated.contains(
            "  private final io.nexusrpc.handler.OperationHandler<java.lang.String,"
                + " java.lang.Void> cancel;\n"
                + "  private final io.nexusrpc.handler.OperationHandler<java.lang.String,"
                + " java.lang.String> start;\n"));
    assertTrue(
        generated,
        generated.contains(
            "  public static DeploymentServiceNexusBindings create(\n"
                + "      test.TestSource.DeploymentFragment deploymentFragment) {\n"
                + "    return new DeploymentServiceNexusBindings(deploymentFragment);\n"));
    assertTrue(
        generated,
        generated.contains(
            "  public static DeploymentServiceNexusBindings register(\n"
                + "      io.temporal.worker.Worker worker,\n"
                + "      test.TestSource.DeploymentFragment deploymentFragment) {\n"
                + "    DeploymentServiceNexusBindings binding = create(deploymentFragment);\n"
                + "    worker.registerNexusServiceImplementation(binding);\n"));
    // The handler factory runs while the binding is constructed, so the @OperationImpl method the
    // Nexus SDK calls hands back the same instance instead of invoking the fragment again.
    assertTrue(
        generated,
        generated.contains(
            "    this.start =\n"
                + "        java.util.Objects.requireNonNull(\n"
                + "            deploymentFragment.start(),\n"
                + "            \"test.TestSource.DeploymentFragment#start() returned a null"
                + " operation handler\");\n"));
    assertTrue(
        generated,
        generated.contains(
            "  public io.nexusrpc.handler.OperationHandler<java.lang.String, java.lang.String>"
                + " start() {\n"
                + "    return start;\n"));
    assertTrue(
        generated,
        generated.contains(
            "Nexus operation {@code test.TestSource.DeploymentService#start} to handwritten"
                + " handler {@code test.TestSource.DeploymentFragment#start}"));
    assertFalse(generated, generated.contains("create()"));
  }

  @Test
  public void composesGeneratedAndHandwrittenOperationHandlers() throws IOException {
    Compilation compilation =
        compileFully(
            "ComposedService",
            source(
                deploymentService("    @Operation String cancel(String input);\n")
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"
                    + fragment(
                        "CancelFragment",
                        handler("OperationHandler<String, String>", "cancel", "input"))));

    assertTrue(compilation.messages, compilation.success);
    String generated = generatedBinding(compilation);
    assertTrue(
        generated,
        generated.contains(
            "    this.cancel =\n"
                + "        java.util.Objects.requireNonNull(\n"
                + "            cancelFragment.cancel(),"));
    assertTrue(generated, generated.contains("    return cancel;\n"));
    assertTrue(
        generated,
        generated.contains(
            "GeneratedNexusOperationHandlers.workflow(\n" + "        test.TestSource."));
    // Operation methods stay sorted by service method name across both provider kinds.
    assertTrue(generated, generated.indexOf("> cancel() {") < generated.indexOf("> start() {"));
  }

  @Test
  public void generatesDeterministicFactoryParametersForMultipleFragments() throws IOException {
    Compilation compilation =
        compileFully(
            "MultipleFragments",
            source(
                deploymentService("    @Operation String cancel(String input);\n")
                    + fragment(
                        "Worker", handler("OperationHandler<String, String>", "start", "input"))
                    + fragment(
                        "Long", handler("OperationHandler<String, String>", "cancel", "input"))));

    assertTrue(compilation.messages, compilation.success);
    String generated = generatedBinding(compilation);
    // Sorted by qualified name rather than declaration order, so output stays deterministic. The
    // parameter names dodge the keyword `long` and the worker parameter `register` already uses.
    assertTrue(
        generated,
        generated.contains(
            "  public static DeploymentServiceNexusBindings create(\n"
                + "      test.TestSource.Long longFragment,\n"
                + "      test.TestSource.Worker worker2) {\n"
                + "    return new DeploymentServiceNexusBindings(longFragment, worker2);\n"));
    assertTrue(
        generated,
        generated.contains(
            "  public static DeploymentServiceNexusBindings register(\n"
                + "      io.temporal.worker.Worker worker,\n"
                + "      test.TestSource.Long longFragment,\n"
                + "      test.TestSource.Worker worker2) {\n"));
  }

  @Test
  public void generatesFragmentHandlersForZeroArgumentAndVoidOperations() throws IOException {
    Compilation compilation =
        compileFully(
            "VoidFragmentOperations",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation void ping();\n"
                    + "  }\n"
                    + fragment(
                        "PingFragment", handler("OperationHandler<Void, Void>", "ping", "null"))));

    assertTrue(compilation.messages, compilation.success);
    String generated = generatedBinding(compilation);
    assertTrue(
        generated,
        generated.contains(
            "  public io.nexusrpc.handler.OperationHandler<java.lang.Void, java.lang.Void>"
                + " ping() {\n"));
  }

  @Test
  public void acceptsFragmentMethodsInheritedFromABaseClass() throws IOException {
    Compilation compilation =
        compileFully(
            "InheritedFragmentMethods",
            source(
                deploymentService("")
                    + "  public abstract static class BaseFragment {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  public static class DeploymentFragment extends BaseFragment {}\n"));

    assertTrue(compilation.messages, compilation.success);
    String generated = generatedBinding(compilation);
    assertTrue(
        generated,
        generated.contains(
            "  public static DeploymentServiceNexusBindings create(\n"
                + "      test.TestSource.DeploymentFragment deploymentFragment) {\n"));
    assertTrue(generated, generated.contains("            deploymentFragment.start(),\n"));
  }

  @Test
  public void identifiesFragmentOperationsByServiceMethodNameNotOperationName() throws IOException {
    Compilation compilation =
        compileFully(
            "FragmentOperationIdentity",
            source(
                "  @Service interface DeploymentService {\n"
                    + "    @Operation(name = \"start-deployment\") String start(String input);\n"
                    + "  }\n"
                    + fragment(
                        "StartFragment",
                        handler("OperationHandler<String, String>", "start", "input"))));

    // The Nexus SDK matches @OperationImpl methods to the service's Java method name, so a fragment
    // method is named after the method rather than after the wire-level operation name.
    assertTrue(compilation.messages, compilation.success);
    String generated = generatedBinding(compilation);
    assertTrue(
        generated,
        generated.contains(
            "  public io.nexusrpc.handler.OperationHandler<java.lang.String, java.lang.String>"
                + " start() {\n"));
  }

  @Test
  public void rejectsOperationProvidedByBothAFragmentAndAMapping() throws IOException {
    Compilation compilation =
        compile(
            "FragmentAndMappingProvider",
            source(
                deploymentService("")
                    + workflowInterface("@WorkflowMethod String run(String input);")
                    + "  static class WorkflowImpl implements WorkflowContract {\n"
                    + "    @Override\n"
                    + "    @WorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"
                    + fragment(
                        "StartFragment",
                        handler("OperationHandler<String, String>", "start", "input"))));

    assertFailureContains(
        compilation,
        "Duplicate provider for test.TestSource.DeploymentService.start; also provided by"
            + " fragment handler method test.TestSource.StartFragment#start");
  }

  @Test
  public void rejectsOperationProvidedByTwoFragments() throws IOException {
    Compilation compilation =
        compile(
            "TwoFragmentProviders",
            source(
                deploymentService("")
                    + fragment(
                        "AlphaFragment",
                        handler("OperationHandler<String, String>", "start", "input"))
                    + fragment(
                        "BetaFragment",
                        handler("OperationHandler<String, String>", "start", "input"))));

    assertFailureContains(
        compilation,
        "Duplicate provider for test.TestSource.DeploymentService.start; also provided by"
            + " fragment handler method test.TestSource.AlphaFragment#start");
  }

  @Test
  public void rejectsServiceOperationsWithNoProvider() throws IOException {
    Compilation compilation =
        compile(
            "FragmentIncompleteService",
            source(
                deploymentService("    @Operation void cancel(String input);\n")
                    + fragment(
                        "StartFragment",
                        handler("OperationHandler<String, String>", "start", "input"))));

    assertFailureContains(compilation, "Incomplete Nexus service; missing operations: [cancel]");
  }

  @Test
  public void rejectsFragmentMethodsWithoutAMatchingServiceOperation() throws IOException {
    Compilation compilation =
        compile(
            "UnknownFragmentOperation",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        handler("OperationHandler<String, String>", "start", "input")
                            + handler("OperationHandler<String, String>", "missing", "input"))));

    assertFailureContains(
        compilation,
        "Nexus service test.TestSource.DeploymentService has no operation method named missing");
  }

  @Test
  public void rejectsFragmentHandlersWithIncompatibleTypes() throws IOException {
    Compilation compilation =
        compile(
            "FragmentTypeMismatch",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        handler(
                            "OperationHandler<Integer, String>", "start", "input.toString()"))));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start returns"
            + " io.nexusrpc.handler.OperationHandler<java.lang.Integer, java.lang.String> but"
            + " Nexus operation test.TestSource.DeploymentService#start requires"
            + " io.nexusrpc.handler.OperationHandler<java.lang.String, java.lang.String>");
  }

  @Test
  public void rejectsFragmentMethodsThatDoNotReturnAnOperationHandler() throws IOException {
    Compilation compilation =
        compile(
            "FragmentReturnType",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    public String start() { return \"nope\"; }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must return"
            + " io.nexusrpc.handler.OperationHandler<Input, Output>");
  }

  @Test
  public void rejectsRawOperationHandlerReturnTypes() throws IOException {
    Compilation compilation =
        compile(
            "RawFragmentReturnType",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    @SuppressWarnings(\"rawtypes\")\n"
                            + "    public OperationHandler start() { return null; }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must return"
            + " io.nexusrpc.handler.OperationHandler<Input, Output>");
  }

  @Test
  public void rejectsFragmentMethodsWithParameters() throws IOException {
    Compilation compilation =
        compile(
            "FragmentMethodParameters",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    public OperationHandler<String, String> start(String unused) {\n"
                            + "      return OperationHandler.sync((c, d, input) -> input);\n"
                            + "    }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must not declare"
            + " parameters");
  }

  @Test
  public void rejectsGenericFragmentMethods() throws IOException {
    Compilation compilation =
        compile(
            "GenericFragmentMethod",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    public <T> OperationHandler<String, String> start() {\n"
                            + "      return OperationHandler.sync((c, d, input) -> input);\n"
                            + "    }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must not declare"
            + " type parameters");
  }

  @Test
  public void rejectsFragmentMethodsWithThrowsClauses() throws IOException {
    Compilation compilation =
        compile(
            "FragmentMethodThrows",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    public OperationHandler<String, String> start()"
                            + " throws java.io.IOException {\n"
                            + "      return OperationHandler.sync((c, d, input) -> input);\n"
                            + "    }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must not declare"
            + " thrown exceptions");
  }

  @Test
  public void rejectsStaticFragmentMethods() throws IOException {
    Compilation compilation =
        compile(
            "StaticFragmentMethod",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    public static OperationHandler<String, String> start() {\n"
                            + "      return OperationHandler.sync((c, d, input) -> input);\n"
                            + "    }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must not be static");
  }

  @Test
  public void rejectsNonPublicFragmentMethods() throws IOException {
    Compilation compilation =
        compile(
            "NonPublicFragmentMethod",
            source(
                deploymentService("")
                    + fragment(
                        "StartFragment",
                        "    @OperationImpl\n"
                            + "    OperationHandler<String, String> start() {\n"
                            + "      return OperationHandler.sync((c, d, input) -> input);\n"
                            + "    }\n")));

    assertFailureContains(
        compilation,
        "Nexus fragment handler method test.TestSource.StartFragment#start must be public");
  }

  @Test
  public void rejectsNonPublicFragmentClasses() throws IOException {
    Compilation compilation =
        compile(
            "NonPublicFragmentClass",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  static class StartFragment {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"));

    assertFailureContains(
        compilation, "@NexusServiceFragment class test.TestSource.StartFragment must be public");
  }

  @Test
  public void rejectsAbstractFragmentClasses() throws IOException {
    Compilation compilation =
        compile(
            "AbstractFragmentClass",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  public abstract static class StartFragment {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "@NexusServiceFragment class test.TestSource.StartFragment must not be abstract");
  }

  @Test
  public void rejectsInnerFragmentClasses() throws IOException {
    Compilation compilation =
        compile(
            "InnerFragmentClass",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  public class StartFragment {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "@NexusServiceFragment class test.TestSource.StartFragment must be a static nested class");
  }

  @Test
  public void rejectsGenericFragmentClasses() throws IOException {
    Compilation compilation =
        compile(
            "GenericFragmentClass",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  public static class StartFragment<T> {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "@NexusServiceFragment class test.TestSource.StartFragment must not declare type"
            + " parameters");
  }

  @Test
  public void rejectsDirectlyRegisterableFragments() throws IOException {
    Compilation compilation =
        compile(
            "RegisterableFragment",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  @io.nexusrpc.handler.ServiceImpl(service = DeploymentService.class)\n"
                    + "  public static class StartFragment {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "@NexusServiceFragment class test.TestSource.StartFragment must not be annotated with"
            + " @ServiceImpl; fragments are composed into the binding generated for"
            + " test.TestSource.DeploymentService instead of being registered themselves");
  }

  @Test
  public void rejectsFragmentsWithoutOperationImplMethods() throws IOException {
    Compilation compilation =
        compile(
            "EmptyFragment",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  public static class StartFragment {\n"
                    + "    public OperationHandler<String, String> start() {\n"
                    + "      return OperationHandler.sync((c, d, input) -> input);\n"
                    + "    }\n"
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "@NexusServiceFragment class test.TestSource.StartFragment declares no @OperationImpl"
            + " methods");
  }

  @Test
  public void rejectsFragmentServiceNotAnnotatedWithService() throws IOException {
    Compilation compilation =
        compile(
            "FragmentServiceNotAService",
            source(
                "  interface NotAService {\n"
                    + "    @Operation String start(String input);\n"
                    + "  }\n"
                    + "  @NexusServiceFragment(service = NotAService.class)\n"
                    + "  public static class StartFragment {\n"
                    + handler("OperationHandler<String, String>", "start", "input")
                    + "  }\n"));

    assertFailureContains(
        compilation,
        "test.TestSource.NotAService in @NexusServiceFragment is not annotated with @Service");
  }

  @Test
  public void rejectsFragmentAnnotationsOnInterfaces() throws IOException {
    Compilation compilation =
        compile(
            "FragmentOnInterface",
            source(
                deploymentService("")
                    + "  @NexusServiceFragment(service = DeploymentService.class)\n"
                    + "  public interface StartFragment {\n"
                    + "    @OperationImpl\n"
                    + "    OperationHandler<String, String> start();\n"
                    + "  }\n"));

    assertFailureContains(compilation, "@NexusServiceFragment is only supported on classes");
  }

  private static String deploymentService(String extraOperations) {
    return "  @Service interface DeploymentService {\n"
        + "    @Operation String start(String input);\n"
        + extraOperations
        + "  }\n";
  }

  private static String fragment(String name, String body) {
    return "  @NexusServiceFragment(service = DeploymentService.class)\n"
        + "  public static class "
        + name
        + " {\n"
        + body
        + "  }\n";
  }

  private static String handler(String handlerType, String name, String result) {
    return "    @OperationImpl\n"
        + "    public "
        + handlerType
        + " "
        + name
        + "() {\n"
        + "      return OperationHandler.sync((context, details, input) -> "
        + result
        + ");\n"
        + "    }\n";
  }

  private static String generatedBinding(Compilation compilation) {
    String generated = compilation.generatedSources.get("test/DeploymentServiceNexusBindings.java");
    assertNotNull(compilation.generatedSources.toString(), generated);
    return generated;
  }

  private static String source(String body) {
    return "package test;\n"
        + "import io.nexusrpc.Operation;\n"
        + "import io.nexusrpc.Service;\n"
        + "import io.nexusrpc.handler.OperationHandler;\n"
        + "import io.nexusrpc.handler.OperationImpl;\n"
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
