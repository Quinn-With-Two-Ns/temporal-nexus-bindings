package io.github.quinnklassen.temporal.nexusannotations.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Map;
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
        "deployment-deployment-7-west",
        InputExpression.compile("deployment-#{id}-#{nested.region}").evaluate(input, String.class));
  }

  @Test
  public void pureInputExpressionPreservesTypedInput() {
    Input input = new Input("deployment-7", new Nested("west"), "public-value");

    assertSame(input, InputExpression.compile("#{input}").evaluate(input, Input.class));
    assertSame(input, InputExpression.compile("#{payload}").evaluate(input, Input.class));
  }

  @Test
  public void evaluatesLiteralsAndLosslessNumericConversions() {
    assertEquals(Integer.valueOf(42), InputExpression.compile("#{42}").evaluate(null, int.class));
    assertEquals(Boolean.TRUE, InputExpression.compile("#{true}").evaluate(null, Boolean.class));
    assertEquals("value", InputExpression.compile("#{'value'}").evaluate(null, String.class));
  }

  @Test
  public void rejectsUnsafeOrMalformedSyntax() {
    assertCompileFailure("#{input.getId()}", "unsupported path syntax");
    assertCompileFailure("#{class}", "forbidden property");
    assertCompileFailure("#{metadata[environment]}", "map keys must be quoted");
    assertCompileFailure("#{id", "unterminated expression");
  }

  @Test
  public void reportsRuntimeTraversalAndConversionFailures() {
    Input input = new Input("deployment-7", null, "public-value");

    assertEvaluationFailure(
        InputExpression.compile("#{nested.region}"), input, String.class, "cannot traverse");
    assertEvaluationFailure(
        InputExpression.compile("#{metadata['missing']}"), input, String.class, "missing map key");
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
      InputExpression expression, Object input, Class<?> targetType, String expected) {
    try {
      expression.evaluate(input, targetType);
      fail("Expected expression evaluation to fail");
    } catch (IllegalArgumentException e) {
      assertContains(e, expected);
    }
  }

  private static void assertContains(IllegalArgumentException exception, String expected) {
    if (!exception.getMessage().contains(expected)) {
      fail("Expected <" + exception.getMessage() + "> to contain <" + expected + ">");
    }
  }

  public static final class Input {
    private final String id;
    private final Nested nested;
    public final String publicField;

    private Input(String id, Nested nested, String publicField) {
      this.id = id;
      this.nested = nested;
      this.publicField = publicField;
    }

    public String getId() {
      return id;
    }

    public Nested getNested() {
      return nested;
    }

    public Map<String, String> getMetadata() {
      return Collections.singletonMap("environment", "production");
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
