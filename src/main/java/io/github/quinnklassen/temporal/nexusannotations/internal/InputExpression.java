package io.github.quinnklassen.temporal.nexusannotations.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compiled, input-only expression used by generated Nexus bindings. */
final class InputExpression {
  private final String source;
  private final List<Segment> segments;
  private final boolean singleExpression;

  static InputExpression compile(String source) {
    if (source == null || source.isEmpty()) {
      throw new IllegalArgumentException("expression is required");
    }
    List<Segment> segments = new ArrayList<>();
    int position = 0;
    while (position < source.length()) {
      int opening = source.indexOf("#{", position);
      if (opening < 0) {
        segments.add(new LiteralSegment(source.substring(position)));
        break;
      }
      if (opening > position) {
        segments.add(new LiteralSegment(source.substring(position, opening)));
      }
      int closing = findClosingBrace(source, opening + 2);
      if (closing < 0) {
        throw new IllegalArgumentException("unterminated expression in \"" + source + "\"");
      }
      String body = source.substring(opening + 2, closing).trim();
      if (body.isEmpty()) {
        throw new IllegalArgumentException("empty expression in \"" + source + "\"");
      }
      segments.add(parseBody(body));
      position = closing + 1;
    }
    if (segments.isEmpty()) {
      segments.add(new LiteralSegment(source));
    }
    boolean singleExpression = segments.size() == 1 && !(segments.get(0) instanceof LiteralSegment);
    return new InputExpression(source, segments, singleExpression);
  }

  private InputExpression(String source, List<Segment> segments, boolean singleExpression) {
    this.source = source;
    this.segments = segments;
    this.singleExpression = singleExpression;
  }

  Object evaluate(Object input, Class<?> targetType) {
    Object value;
    if (singleExpression) {
      value = segments.get(0).evaluate(input);
    } else {
      StringBuilder result = new StringBuilder();
      for (Segment segment : segments) {
        Object part = segment.evaluate(input);
        if (part != null) {
          result.append(part);
        }
      }
      value = result.toString();
    }
    return coerce(value, targetType, source);
  }

  static Object coerce(Object value, Class<?> targetType, String source) {
    Class<?> boxedTarget = box(targetType);
    if (value == null) {
      if (targetType.isPrimitive()) {
        throw new IllegalArgumentException(
            "expression \"" + source + "\" evaluated to null for " + targetType.getName());
      }
      return null;
    }
    if (boxedTarget.isInstance(value)) {
      return value;
    }
    if (boxedTarget == String.class) {
      return String.valueOf(value);
    }
    if (value instanceof Number && Number.class.isAssignableFrom(boxedTarget)) {
      return convertNumber((Number) value, boxedTarget, source);
    }
    if (boxedTarget == Character.class && value instanceof String) {
      String string = (String) value;
      if (string.length() == 1) {
        return string.charAt(0);
      }
    }
    throw new IllegalArgumentException(
        "expression \""
            + source
            + "\" produced "
            + value.getClass().getName()
            + ", which cannot be converted to "
            + targetType.getName());
  }

  private static Number convertNumber(Number value, Class<?> targetType, String source) {
    BigDecimal decimal = new BigDecimal(value.toString());
    try {
      if (targetType == Byte.class) return decimal.byteValueExact();
      if (targetType == Short.class) return decimal.shortValueExact();
      if (targetType == Integer.class) return decimal.intValueExact();
      if (targetType == Long.class) return decimal.longValueExact();
      if (targetType == Float.class) return decimal.floatValue();
      if (targetType == Double.class) return decimal.doubleValue();
      if (targetType == BigDecimal.class) return decimal;
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "expression \"" + source + "\" cannot be converted losslessly to " + targetType.getName(),
          e);
    }
    throw new IllegalArgumentException("unsupported numeric target " + targetType.getName());
  }

  private static Segment parseBody(String body) {
    if ("payload".equals(body) || "input".equals(body)) {
      return new PayloadSegment();
    }
    if ("null".equals(body)) return new ConstantSegment(null);
    if ("true".equals(body)) return new ConstantSegment(Boolean.TRUE);
    if ("false".equals(body)) return new ConstantSegment(Boolean.FALSE);
    if (isQuoted(body)) {
      return new ConstantSegment(unquote(body));
    }
    if (body.matches("-?[0-9]+")) {
      try {
        return new ConstantSegment(Long.valueOf(body));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("integer literal is out of range: " + body, e);
      }
    }
    if (body.matches("-?[0-9]+\\.[0-9]+")) {
      return new ConstantSegment(new BigDecimal(body));
    }
    return parsePath(body);
  }

  private static Segment parsePath(String body) {
    List<PathStep> steps = new ArrayList<>();
    int position = 0;
    while (position < body.length()) {
      if (body.charAt(position) == '.') {
        position++;
      }
      if (position >= body.length()) {
        throw new IllegalArgumentException("path cannot end with '.' in \"#{" + body + "}\"");
      }
      if (body.charAt(position) == '[') {
        int closing = body.indexOf(']', position);
        if (closing < 0) {
          throw new IllegalArgumentException("unterminated map key in \"#{" + body + "}\"");
        }
        String key = body.substring(position + 1, closing).trim();
        if (!isQuoted(key)) {
          throw new IllegalArgumentException("map keys must be quoted in \"#{" + body + "}\"");
        }
        steps.add(new MapKeyStep(unquote(key)));
        position = closing + 1;
        continue;
      }
      int start = position;
      while (position < body.length() && Character.isJavaIdentifierPart(body.charAt(position))) {
        position++;
      }
      if (start == position || !Character.isJavaIdentifierStart(body.charAt(start))) {
        throw new IllegalArgumentException("unsupported path syntax in \"#{" + body + "}\"");
      }
      String property = body.substring(start, position);
      rejectProperty(property);
      steps.add(new PropertyStep(property));
      if (position < body.length()
          && body.charAt(position) != '.'
          && body.charAt(position) != '[') {
        throw new IllegalArgumentException("unsupported path syntax in \"#{" + body + "}\"");
      }
    }
    return new PathSegment(steps);
  }

  private static void rejectProperty(String property) {
    if ("class".equals(property)
        || "getClass".equals(property)
        || "classLoader".equals(property)
        || "declaringClass".equals(property)) {
      throw new IllegalArgumentException("forbidden property \"" + property + "\"");
    }
  }

  private static int findClosingBrace(String source, int position) {
    char quote = 0;
    for (int i = position; i < source.length(); i++) {
      char current = source.charAt(i);
      if (quote != 0) {
        if (current == quote && source.charAt(i - 1) != '\\') {
          quote = 0;
        }
      } else if (current == '\'' || current == '"') {
        quote = current;
      } else if (current == '}') {
        return i;
      }
    }
    return -1;
  }

  private static boolean isQuoted(String value) {
    return value.length() >= 2
        && ((value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')
            || (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'));
  }

  private static String unquote(String value) {
    return value.substring(1, value.length() - 1);
  }

  private static Class<?> box(Class<?> type) {
    if (!type.isPrimitive()) return type;
    if (type == boolean.class) return Boolean.class;
    if (type == byte.class) return Byte.class;
    if (type == short.class) return Short.class;
    if (type == int.class) return Integer.class;
    if (type == long.class) return Long.class;
    if (type == float.class) return Float.class;
    if (type == double.class) return Double.class;
    if (type == char.class) return Character.class;
    return type;
  }

  private interface Segment {
    Object evaluate(Object input);
  }

  private static final class LiteralSegment implements Segment {
    private final String value;

    private LiteralSegment(String value) {
      this.value = value;
    }

    @Override
    public Object evaluate(Object input) {
      return value;
    }
  }

  private static final class ConstantSegment implements Segment {
    private final Object value;

    private ConstantSegment(Object value) {
      this.value = value;
    }

    @Override
    public Object evaluate(Object input) {
      return value;
    }
  }

  private static final class PayloadSegment implements Segment {
    @Override
    public Object evaluate(Object input) {
      return input;
    }
  }

  private static final class PathSegment implements Segment {
    private final List<PathStep> steps;

    private PathSegment(List<PathStep> steps) {
      this.steps = steps;
    }

    @Override
    public Object evaluate(Object input) {
      Object value = input;
      for (PathStep step : steps) {
        if (value == null) {
          throw new IllegalArgumentException("cannot traverse through null");
        }
        value = step.evaluate(value);
      }
      return value;
    }
  }

  private interface PathStep {
    Object evaluate(Object value);
  }

  private static final class MapKeyStep implements PathStep {
    private final String key;

    private MapKeyStep(String key) {
      this.key = key;
    }

    @Override
    public Object evaluate(Object value) {
      if (!(value instanceof Map)) {
        throw new IllegalArgumentException("map key access requires a Map");
      }
      Map<?, ?> map = (Map<?, ?>) value;
      if (!map.containsKey(key)) {
        throw new IllegalArgumentException("missing map key \"" + key + "\"");
      }
      return map.get(key);
    }
  }

  private static final class PropertyStep implements PathStep {
    private final String property;

    private PropertyStep(String property) {
      this.property = property;
    }

    @Override
    public Object evaluate(Object value) {
      Class<?> type = value.getClass();
      String suffix = property.substring(0, 1).toUpperCase() + property.substring(1);
      Method method = findMethod(type, "get" + suffix);
      if (method == null) {
        method = findMethod(type, "is" + suffix);
      }
      if (method != null) {
        try {
          return method.invoke(value);
        } catch (ReflectiveOperationException e) {
          throw new IllegalArgumentException("failed reading property \"" + property + "\"", e);
        }
      }
      try {
        Field field = type.getField(property);
        return field.get(value);
      } catch (NoSuchFieldException e) {
        throw new IllegalArgumentException(
            "property \"" + property + "\" does not exist on " + type.getName(), e);
      } catch (ReflectiveOperationException e) {
        throw new IllegalArgumentException("failed reading field \"" + property + "\"", e);
      }
    }

    private static Method findMethod(Class<?> type, String name) {
      try {
        Method method = type.getMethod(name);
        return method.getParameterTypes().length == 0 ? method : null;
      } catch (NoSuchMethodException e) {
        return null;
      }
    }
  }
}
