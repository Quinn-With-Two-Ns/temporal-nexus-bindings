package io.github.quinn_with_two_ns.temporal.nexus.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
