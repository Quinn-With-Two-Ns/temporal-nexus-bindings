package io.github.quinn_with_two_ns.temporal.nexus.internal;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Compiled payload and request-metadata expression used by generated Nexus bindings. */
final class InputExpression {
  private final ExpressionModel model;

  static InputExpression compile(String source) {
    return new InputExpression(ExpressionModel.parse(source));
  }

  private InputExpression(ExpressionModel model) {
    this.model = model;
  }

  @Nullable Object evaluate(@Nullable Object input, Class<?> targetType) {
    return evaluate(input, null, Collections.<String, String>emptyMap(), targetType);
  }

  @Nullable Object evaluate(
      @Nullable Object input,
      @Nullable String requestId,
      Map<String, String> headers,
      Class<?> targetType) {
    @Nullable Object value;
    if (model.singleExpression) {
      value = evaluate(model.segments.get(0), input, requestId, headers);
    } else {
      StringBuilder result = new StringBuilder();
      for (ExpressionModel.Segment segment : model.segments) {
        @Nullable Object part = evaluate(segment, input, requestId, headers);
        if (part == null) {
          throw new IllegalArgumentException(
              "expression \"" + model.source + "\" evaluated to null within a template");
        }
        result.append(part);
      }
      value = result.toString();
    }
    return coerce(value, targetType, model.source);
  }

  static @Nullable Object coerce(@Nullable Object value, Class<?> targetType, String source) {
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

  private static @Nullable Object evaluate(
      ExpressionModel.Segment segment,
      @Nullable Object input,
      @Nullable String requestId,
      Map<String, String> headers) {
    if (segment instanceof ExpressionModel.LiteralSegment) {
      return ((ExpressionModel.LiteralSegment) segment).value;
    }
    if (segment instanceof ExpressionModel.ConstantSegment) {
      return ((ExpressionModel.ConstantSegment) segment).value;
    }
    if (segment instanceof ExpressionModel.PayloadSegment) {
      return input;
    }
    return evaluatePath((ExpressionModel.PathSegment) segment, input, requestId, headers);
  }

  private static @Nullable Object evaluatePath(
      ExpressionModel.PathSegment path,
      @Nullable Object input,
      @Nullable String requestId,
      Map<String, String> headers) {
    @Nullable Object value;
    if (path.root == ExpressionModel.Root.REQUEST_ID) {
      value = requestId;
    } else if (path.root == ExpressionModel.Root.HEADERS) {
      value = headers;
    } else {
      value = input;
    }
    for (ExpressionModel.PathStep step : path.steps) {
      if (value == null) {
        throw new IllegalArgumentException("cannot traverse through null");
      }
      if (step instanceof ExpressionModel.PropertyStep) {
        value = readProperty(value, ((ExpressionModel.PropertyStep) step).property);
      } else if (step instanceof ExpressionModel.MapKeyStep) {
        value = readMapKey(value, ((ExpressionModel.MapKeyStep) step).key);
      } else {
        value = readIndex(value, ((ExpressionModel.IndexStep) step).index);
      }
    }
    return value;
  }

  private static @Nullable Object readMapKey(Object value, String key) {
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("map key access requires a Map");
    }
    Map<?, ?> map = (Map<?, ?>) value;
    if (!map.containsKey(key)) {
      throw new IllegalArgumentException("missing map key \"" + key + "\"");
    }
    return map.get(key);
  }

  private static @Nullable Object readIndex(Object value, int index) {
    int size;
    if (value instanceof List) {
      List<?> list = (List<?>) value;
      size = list.size();
      if (index < size) {
        return list.get(index);
      }
    } else if (value.getClass().isArray()) {
      size = Array.getLength(value);
      if (index < size) {
        return Array.get(value, index);
      }
    } else {
      throw new IllegalArgumentException("index access requires a List or array");
    }
    throw new IllegalArgumentException("index " + index + " out of bounds for size " + size);
  }

  private static @Nullable Object readProperty(Object value, String property) {
    Class<?> type = value.getClass();
    String suffix = property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
    @Nullable Method method = findMethod(type, "get" + suffix);
    if (method == null) {
      method = findMethod(type, "is" + suffix);
    }
    if (method == null) {
      method = recordAccessor(type, property);
    }
    if (method != null) {
      try {
        return method.invoke(value);
      } catch (ReflectiveOperationException e) {
        // A handler-side fault, not invalid caller input; must not surface as BAD_REQUEST.
        throw new IllegalStateException("failed reading property \"" + property + "\"", e);
      }
    }
    try {
      Field field = type.getField(property);
      return field.get(value);
    } catch (NoSuchFieldException e) {
      throw new IllegalArgumentException(
          "property \"" + property + "\" does not exist on " + type.getName(), e);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("failed reading field \"" + property + "\"", e);
    }
  }

  private static @Nullable Method findMethod(Class<?> type, String name) {
    final Method method;
    try {
      method = type.getMethod(name);
    } catch (NoSuchMethodException e) {
      return null;
    }
    if (method.getParameterTypes().length != 0) {
      return null;
    }
    if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
      return method;
    }
    // The method is declared on a non-public class, so invoking it directly throws
    // IllegalAccessException. Prefer an equivalent declared on a public supertype.
    @Nullable Method accessible = accessibleEquivalent(type, name);
    return accessible == null ? method : accessible;
  }

  private static @Nullable Method accessibleEquivalent(@Nullable Class<?> type, String name) {
    if (type == null) {
      return null;
    }
    if (Modifier.isPublic(type.getModifiers())) {
      try {
        Method candidate = type.getMethod(name);
        if (candidate.getParameterTypes().length == 0
            && Modifier.isPublic(candidate.getDeclaringClass().getModifiers())) {
          return candidate;
        }
      } catch (NoSuchMethodException e) {
        // Keep searching supertypes.
      }
    }
    for (Class<?> implemented : type.getInterfaces()) {
      @Nullable Method candidate = accessibleEquivalent(implemented, name);
      if (candidate != null) {
        return candidate;
      }
    }
    return accessibleEquivalent(type.getSuperclass(), name);
  }

  /**
   * Returns the same-named accessor when {@code property} is a genuine record component, or {@code
   * null} otherwise. Detection is restricted to record types and to a component's implicit instance
   * field so an ordinary public zero-argument method on a record is not exposed as a property.
   */
  private static @Nullable Method recordAccessor(Class<?> type, String property) {
    if (!isRecord(type) || !hasInstanceField(type, property)) {
      return null;
    }
    return findMethod(type, property);
  }

  private static boolean isRecord(Class<?> type) {
    // Class.isRecord() is Java 16+; a record's direct superclass is always java.lang.Record, which
    // lets this detection compile for release 8 while still recognizing records at runtime.
    Class<?> superclass = type.getSuperclass();
    return superclass != null && "java.lang.Record".equals(superclass.getName());
  }

  private static boolean hasInstanceField(Class<?> type, String property) {
    // A record cannot declare instance fields beyond its components, so a matching non-static field
    // proves the property is a genuine component rather than a derived accessor.
    for (Field field : type.getDeclaredFields()) {
      if (field.getName().equals(property) && !Modifier.isStatic(field.getModifiers())) {
        return true;
      }
    }
    return false;
  }
}
