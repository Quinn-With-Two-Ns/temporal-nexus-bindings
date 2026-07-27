package io.github.quinn_with_two_ns.temporal.nexus.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class InputExpressionTest {
  @Test
  public void evaluatesBeanFieldsMapsAndTemplates() {
    Input input = new Input("deployment-7", new Nested("west"), "public-value");

    assertEquals("deployment-7", InputExpression.compile("#{id}").evaluate(input, String.class));
    assertEquals("west", InputExpression.compile("#{nested.region}").evaluate(input, String.class));
    assertEquals(
        "public-value", InputExpression.compile("#{publicField}").evaluate(input, String.class));
    assertEquals(
        "production",
        InputExpression.compile("#{metadata['environment']}").evaluate(input, String.class));
    assertEquals(
        "west", InputExpression.compile("#{nestedItems[0].region}").evaluate(input, String.class));
    assertEquals(
        "west", InputExpression.compile("#{nestedArray[0].region}").evaluate(input, String.class));
    assertEquals(
        "deployment-deployment-7-west",
        InputExpression.compile("deployment-#{id}-#{nested.region}").evaluate(input, String.class));
  }

  @Test
  public void pureInputExpressionPreservesTypedInput() {
    Input input = new Input("deployment-7", new Nested("west"), "public-value");

    assertSame(input, InputExpression.compile("#{input}").evaluate(input, Input.class));
    assertSame(input, InputExpression.compile("#{payload}").evaluate(input, Input.class));
    assertEquals(
        "deployment-7", InputExpression.compile("#{input.id}").evaluate(input, String.class));
    assertEquals(
        "deployment-7", InputExpression.compile("#{payload.id}").evaluate(input, String.class));
  }

  @Test
  public void evaluatesRecordComponents() throws Exception {
    // Records require Java 16+, which the release-8 test source set cannot express directly, so the
    // fixture is compiled and loaded at runtime to exercise genuine record-component reflection.
    Object input = compileRecordInput();

    assertEquals("deployment-7", InputExpression.compile("#{id}").evaluate(input, String.class));
    assertEquals("west", InputExpression.compile("#{nested.region}").evaluate(input, String.class));
    assertEquals(
        "production",
        InputExpression.compile("#{metadata['environment']}").evaluate(input, String.class));
    assertEquals(
        "deployment-deployment-7-west",
        InputExpression.compile("deployment-#{id}-#{nested.region}").evaluate(input, String.class));
    assertEvaluationFailure(
        InputExpression.compile("#{derived}"), input, String.class, "does not exist");
  }

  @Test
  public void evaluatesNexusRequestMetadata() {
    Input input = new Input("payload-request", new Nested("west"), "public-value");
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.put("X-Deployment-Environment", "production");

    assertEquals(
        "nexus-request-7",
        InputExpression.compile("#{nexus.requestId}")
            .evaluate(input, "nexus-request-7", headers, String.class));
    assertEquals(
        "production",
        InputExpression.compile("#{nexus.headers['x-deployment-environment']}")
            .evaluate(input, "nexus-request-7", headers, String.class));
    assertSame(
        headers,
        InputExpression.compile("#{nexus.headers}")
            .evaluate(input, "nexus-request-7", headers, Map.class));
    assertEquals(
        "deployment-nexus-request-7-production",
        InputExpression.compile(
                "deployment-#{nexus.requestId}-#{nexus.headers['x-deployment-environment']}")
            .evaluate(input, "nexus-request-7", headers, String.class));
    assertEquals(
        "payload-request",
        InputExpression.compile("#{requestId}")
            .evaluate(input, "nexus-request-7", headers, String.class));
    assertEquals(
        "payload",
        InputExpression.compile("#{headers['source']}")
            .evaluate(input, "nexus-request-7", headers, String.class));
  }

  @Test
  public void evaluatesRootContainers() {
    assertEquals(
        "first", InputExpression.compile("#{[0]}").evaluate(Arrays.asList("first"), String.class));
    assertEquals(
        "first", InputExpression.compile("#{[0]}").evaluate(new String[] {"first"}, String.class));
    assertEquals(
        "value",
        InputExpression.compile("#{['key']}")
            .evaluate(Collections.singletonMap("key", "value"), String.class));
  }

  @Test
  public void evaluatesLiteralsAndLosslessNumericConversions() {
    assertEquals(Integer.valueOf(42), InputExpression.compile("#{42}").evaluate(null, int.class));
    assertEquals(true, InputExpression.compile("#{true}").evaluate(null, Boolean.class));
    assertEquals("value", InputExpression.compile("#{'value'}").evaluate(null, String.class));
  }

  @Test
  public void rejectsUnsafeOrMalformedSyntax() {
    assertCompileFailure("#{input.getId()}", "unsupported path syntax");
    assertCompileFailure("#{class}", "forbidden property");
    assertCompileFailure(
        "#{metadata[environment]}", "quoted map key or non-negative integer index");
    assertCompileFailure("#{nestedItems[-1]}", "quoted map key or non-negative integer index");
    assertCompileFailure("#{nexus}", "requires requestId or headers");
    assertCompileFailure("#{nexus.unknown}", "unsupported Nexus metadata property");
    assertCompileFailure("#{id", "unterminated expression");
    assertCompileFailure("#{nestedItems[0]region}", "unsupported path syntax");
    assertCompileFailure("#{.id}", "path cannot start with '.'");
    assertCompileFailure("#{'a' 'b'}", "unsupported path syntax");
    assertCompileFailure("#{metadata['key'}", "unterminated container access");
  }

  @Test
  public void unescapesQuotedStringsAndBracketKeys() {
    assertEquals("it's", InputExpression.compile("#{'it\\'s'}").evaluate(null, String.class));
    assertEquals("a\\b", InputExpression.compile("#{'a\\\\b'}").evaluate(null, String.class));
    assertEquals(
        "value",
        InputExpression.compile("#{['a]b']}")
            .evaluate(Collections.singletonMap("a]b", "value"), String.class));
  }

  @Test
  public void reportsRuntimeTraversalAndConversionFailures() {
    Input input = new Input("deployment-7", null, "public-value");
    Input populatedInput = new Input("deployment-7", new Nested("west"), "public-value");

    assertEvaluationFailure(
        InputExpression.compile("#{nested.region}"), input, String.class, "cannot traverse");
    assertEvaluationFailure(
        InputExpression.compile("#{metadata['missing']}"), input, String.class, "missing map key");
    assertEvaluationFailure(
        InputExpression.compile("#{nestedItems[1]}"),
        populatedInput,
        Nested.class,
        "index 1 out of bounds");
    assertEvaluationFailure(
        InputExpression.compile("#{nested[0]}"),
        populatedInput,
        Nested.class,
        "requires a List or array");
    assertEvaluationFailure(
        InputExpression.compile("#{id}"), input, Integer.class, "cannot be converted");
  }

  private static Object compileRecordInput() throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("Tests require a JDK");
    }
    Path root = Files.createTempDirectory("nexus-record-fixture");
    try {
      String recordSource =
          "package recordfixture;\n"
              + "public record RecordInput(String id, Nested nested,"
              + " java.util.Map<String, String> metadata) {\n"
              + "  public record Nested(String region) {}\n"
              + "  public String derived() { return id + \"!\"; }\n"
              + "}\n";
      Path sourceFile = root.resolve("recordfixture/RecordInput.java");
      Files.createDirectories(sourceFile.getParent());
      Files.write(sourceFile, recordSource.getBytes(StandardCharsets.UTF_8));
      Path classes = Files.createDirectories(root.resolve("classes"));
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      try (StandardJavaFileManager fileManager =
          compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
        Iterable<? extends JavaFileObject> units =
            fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile.toFile()));
        List<String> options = Arrays.asList("-d", classes.toString(), "--release", "17");
        if (!compiler.getTask(null, fileManager, diagnostics, options, null, units).call()) {
          fail(diagnostics.getDiagnostics().toString());
        }
      }
      try (URLClassLoader loader =
          new URLClassLoader(
              new URL[] {classes.toUri().toURL()}, InputExpressionTest.class.getClassLoader())) {
        Class<?> nestedClass = Class.forName("recordfixture.RecordInput$Nested", true, loader);
        Class<?> inputClass = Class.forName("recordfixture.RecordInput", true, loader);
        Object nested = nestedClass.getConstructor(String.class).newInstance("west");
        return inputClass
            .getConstructor(String.class, nestedClass, Map.class)
            .newInstance(
                "deployment-7", nested, Collections.singletonMap("environment", "production"));
      }
    } finally {
      try (Stream<Path> paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void rejectsNullTemplateParts() {
    NullableId nullId = new NullableId(null);
    NullableId presentId = new NullableId("deployment-7");

    assertEvaluationFailure(
        InputExpression.compile("wf-#{id}"),
        nullId,
        String.class,
        "evaluated to null within a template");
    assertEquals(
        "wf-deployment-7", InputExpression.compile("wf-#{id}").evaluate(presentId, String.class));
  }

  public static final class NullableId {
    private final @Nullable String id;

    NullableId(@Nullable String id) {
      this.id = id;
    }

    public @Nullable String getId() {
      return id;
    }
  }

  private static void assertCompileFailure(String source, String expected) {
    try {
      InputExpression.compile(source);
      fail("Expected expression compilation to fail");
    } catch (IllegalArgumentException e) {
      assertContains(e, expected);
    }
  }

  private static void assertEvaluationFailure(
      InputExpression expression, @Nullable Object input, Class<?> targetType, String expected) {
    try {
      expression.evaluate(input, targetType);
      fail("Expected expression evaluation to fail");
    } catch (IllegalArgumentException e) {
      assertContains(e, expected);
    }
  }

  private static void assertContains(IllegalArgumentException exception, String expected) {
    String message = String.valueOf(exception.getMessage());
    if (!message.contains(expected)) {
      fail("Expected <" + message + "> to contain <" + expected + ">");
    }
  }

  public static final class Input {
    private final String id;
    private final @Nullable Nested nested;
    public final String publicField;

    private Input(String id, @Nullable Nested nested, String publicField) {
      this.id = id;
      this.nested = nested;
      this.publicField = publicField;
    }

    public String getId() {
      return id;
    }

    public @Nullable Nested getNested() {
      return nested;
    }

    public Map<String, String> getMetadata() {
      return Collections.singletonMap("environment", "production");
    }

    public String getRequestId() {
      return id;
    }

    public Map<String, String> getHeaders() {
      return Collections.singletonMap("source", "payload");
    }

    public List<@Nullable Nested> getNestedItems() {
      return Collections.singletonList(nested);
    }

    public @Nullable Nested[] getNestedArray() {
      return new @Nullable Nested[] {nested};
    }
  }

  public static final class Nested {
    private final String region;

    private Nested(String region) {
      this.region = region;
    }

    public String getRegion() {
      return region;
    }
  }
}
