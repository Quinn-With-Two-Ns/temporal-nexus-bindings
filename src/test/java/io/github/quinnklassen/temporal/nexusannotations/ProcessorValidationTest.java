package io.github.quinnklassen.temporal.nexusannotations;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.quinnklassen.temporal.nexusannotations.internal.NexusAnnotatedHandlerProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
                    + "    @NexusWorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input}\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"
                    + "  static class CancelWorkflowImpl implements CancelWorkflow {\n"
                    + "    @Override\n"
                    + "    @NexusSignalWorkflowOperation(service = DeploymentService.class,"
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
                    + "    @NexusWorkflowOperation(service = DeploymentService.class,"
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
            + "    @NexusWorkflowOperation(service = DeploymentService.class,"
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
                    + "    @NexusUpdateWorkflowOperation(service = DeploymentService.class,"
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
                    + "    @NexusWorkflowOperation(service = DeploymentService.class,"
                    + " name = \"start\", workflowId = \"#{input\")\n"
                    + "    public String run(String input) { return input; }\n"
                    + "  }\n"));

    assertFailureContains(compilation, "Invalid workflowId expression");
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
                    + "    @NexusActivityOperation(service = DeploymentService.class,"
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
                    + "    @NexusWorkflowOperation(service = DeploymentService.class,"
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
        + "import io.github.quinnklassen.temporal.nexusannotations.*;\n"
        + "import io.temporal.workflow.*;\n"
        + "public class TestSource {\n"
        + body
        + "}\n";
  }

  private static String workflowInterface(String method) {
    return "  @WorkflowInterface interface WorkflowContract {\n    " + method + "\n  }\n";
  }

  private static Compilation compile(String name, String source) throws IOException {
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
        List<String> options =
            Arrays.asList(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                "-s",
                generated.toString(),
                "-source",
                "8",
                "-target",
                "8",
                "-proc:only");
        JavaCompiler.CompilationTask task =
            compiler.getTask(null, fileManager, diagnostics, options, null, units);
        task.setProcessors(Collections.singletonList(new NexusAnnotatedHandlerProcessor()));
        boolean success = task.call();
        String messages =
            diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        List<String> generatedFiles;
        try (Stream<Path> generatedPaths = Files.walk(generated)) {
          generatedFiles =
              generatedPaths
                  .filter(Files::isRegularFile)
                  .map(path -> generated.relativize(path).toString().replace('\\', '/'))
                  .sorted()
                  .collect(Collectors.toList());
        }
        return new Compilation(success, messages, generatedFiles);
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

    private Compilation(boolean success, String messages, List<String> generatedFiles) {
      this.success = success;
      this.messages = messages;
      this.generatedFiles = generatedFiles;
    }
  }
}
