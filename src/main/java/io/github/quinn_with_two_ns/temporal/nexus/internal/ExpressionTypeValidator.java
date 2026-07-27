package io.github.quinn_with_two_ns.temporal.nexus.internal;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.jspecify.annotations.Nullable;

/** Infers expression types from source models and verifies runtime-supported coercions. */
final class ExpressionTypeValidator {
  private static final String LIST = List.class.getName();
  private static final String MAP = java.util.Map.class.getName();
  private static final String NUMBER = Number.class.getName();
  private static final String STRING = String.class.getName();
  private static final String CHARACTER = Character.class.getName();
  private static final String OBJECT = Object.class.getName();

  private final Elements elements;
  private final Types types;

  ExpressionTypeValidator(Elements elements, Types types) {
    this.elements = elements;
    this.types = types;
  }

  void validate(ExpressionModel expression, TypeMirror inputType, TypeMirror targetType) {
    InferredType result = infer(expression, inputType);
    if (!result.dynamic && !canCoerce(Objects.requireNonNull(result.type), targetType)) {
      throw new IllegalArgumentException(
          "expression produces " + result.type + ", which cannot be converted to " + targetType);
    }
  }

  private InferredType infer(ExpressionModel expression, TypeMirror inputType) {
    if (!expression.singleExpression) {
      TypeMirror string = type(STRING);
      for (ExpressionModel.Segment segment : expression.segments) {
        if (segment instanceof ExpressionModel.LiteralSegment) {
          continue;
        }
        InferredType part = infer(segment, inputType);
        if (part.dynamic) {
          continue;
        }
        TypeMirror partType = Objects.requireNonNull(part.type);
        if (partType.getKind() == TypeKind.NULL) {
          throw new IllegalArgumentException("template expressions must not evaluate to null");
        }
        if (!canCoerce(partType, string)) {
          throw new IllegalArgumentException(
              "template expression produces "
                  + partType
                  + ", which cannot be converted to "
                  + string);
        }
      }
      return known(string);
    }
    return infer(expression.segments.get(0), inputType);
  }

  private InferredType infer(ExpressionModel.Segment segment, TypeMirror inputType) {
    if (segment instanceof ExpressionModel.LiteralSegment) {
      return known(type(STRING));
    }
    if (segment instanceof ExpressionModel.PayloadSegment) {
      return known(inputType);
    }
    if (segment instanceof ExpressionModel.ConstantSegment) {
      Object value = ((ExpressionModel.ConstantSegment) segment).value;
      return known(value == null ? types.getNullType() : type(value.getClass().getName()));
    }
    return inferPath((ExpressionModel.PathSegment) segment, inputType);
  }

  private InferredType inferPath(ExpressionModel.PathSegment path, TypeMirror inputType) {
    InferredType current;
    if (path.root == ExpressionModel.Root.REQUEST_ID) {
      current = known(type(STRING));
    } else if (path.root == ExpressionModel.Root.HEADERS) {
      TypeElement map = requireTypeElement(MAP);
      TypeMirror string = type(STRING);
      current = known(types.getDeclaredType(map, string, string));
    } else {
      current = known(inputType);
    }
    for (ExpressionModel.PathStep step : path.steps) {
      if (current.dynamic) {
        continue;
      }
      TypeMirror currentType = Objects.requireNonNull(current.type);
      if (step instanceof ExpressionModel.PropertyStep) {
        current = property(currentType, ((ExpressionModel.PropertyStep) step).property);
      } else if (step instanceof ExpressionModel.MapKeyStep) {
        current = mapValue(currentType);
      } else {
        current = indexedValue(currentType);
      }
    }
    return current;
  }

  private InferredType property(TypeMirror ownerType, String property) {
    TypeMirror normalizedOwner = readableType(ownerType);
    if (normalizedOwner.getKind() != TypeKind.DECLARED) {
      throw new IllegalArgumentException(
          "property \"" + property + "\" cannot be read from " + normalizedOwner);
    }
    DeclaredType declaredOwner = (DeclaredType) normalizedOwner;
    TypeElement owner = (TypeElement) declaredOwner.asElement();
    String suffix = property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
    @Nullable TypeMirror getter = methodReturnType(declaredOwner, owner, "get" + suffix);
    if (getter == null) {
      getter = methodReturnType(declaredOwner, owner, "is" + suffix);
    }
    if (getter == null) {
      getter = recordComponentReturnType(declaredOwner, owner, property);
    }
    if (getter != null) {
      return known(getter.getKind() == TypeKind.VOID ? types.getNullType() : getter);
    }
    for (VariableElement field : ElementFilter.fieldsIn(elements.getAllMembers(owner))) {
      if (field.getSimpleName().contentEquals(property)
          && field.getModifiers().contains(Modifier.PUBLIC)) {
        return known(types.asMemberOf(declaredOwner, field));
      }
    }
    throw new IllegalArgumentException(
        "property \"" + property + "\" does not exist on " + normalizedOwner);
  }

  private @Nullable TypeMirror methodReturnType(
      DeclaredType declaredOwner, TypeElement owner, String methodName) {
    for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(owner))) {
      if (method.getSimpleName().contentEquals(methodName)
          && method.getModifiers().contains(Modifier.PUBLIC)
          && method.getParameters().isEmpty()) {
        return ((ExecutableType) types.asMemberOf(declaredOwner, method)).getReturnType();
      }
    }
    return null;
  }

  /**
   * Returns the accessor return type when {@code property} is a genuine record component of {@code
   * owner}, or {@code null} otherwise. Detection is intentionally restricted to record types and to
   * a component's implicit same-named instance field, so an ordinary public zero-argument method on
   * a record is not exposed as a readable property.
   */
  private @Nullable TypeMirror recordComponentReturnType(
      DeclaredType declaredOwner, TypeElement owner, String property) {
    if (!isRecord(owner) || !hasInstanceField(owner, property)) {
      return null;
    }
    return methodReturnType(declaredOwner, owner, property);
  }

  private static boolean isRecord(TypeElement type) {
    // ElementKind.RECORD is Java 16+; compare by name so the processor still compiles for release
    // 8.
    return type.getKind().name().equals("RECORD");
  }

  private boolean hasInstanceField(TypeElement owner, String property) {
    // A record cannot declare instance fields beyond its components, so a matching non-static field
    // proves the property is a genuine component rather than a derived accessor.
    for (VariableElement field : ElementFilter.fieldsIn(elements.getAllMembers(owner))) {
      if (field.getSimpleName().contentEquals(property)
          && !field.getModifiers().contains(Modifier.STATIC)) {
        return true;
      }
    }
    return false;
  }

  private InferredType mapValue(TypeMirror ownerType) {
    @Nullable DeclaredType mapType = findSupertype(ownerType, MAP, new HashSet<String>());
    if (mapType == null) {
      throw new IllegalArgumentException(
          "map key access requires a Map, but expression type is " + ownerType);
    }
    List<? extends TypeMirror> arguments = mapType.getTypeArguments();
    if (arguments.size() != 2) {
      return dynamic();
    }
    TypeMirror keyType = readableType(arguments.get(0));
    if (!types.isAssignable(type(STRING), keyType)) {
      throw new IllegalArgumentException(
          "quoted map keys require a String-compatible key type, but Map key type is " + keyType);
    }
    TypeMirror valueType = readableType(arguments.get(1));
    return isUnbounded(arguments.get(1)) ? dynamic() : known(valueType);
  }

  private InferredType indexedValue(TypeMirror ownerType) {
    TypeMirror normalizedOwner = readableType(ownerType);
    if (normalizedOwner.getKind() == TypeKind.ARRAY) {
      return known(((ArrayType) normalizedOwner).getComponentType());
    }
    @Nullable DeclaredType listType = findSupertype(normalizedOwner, LIST, new HashSet<String>());
    if (listType == null) {
      throw new IllegalArgumentException(
          "index access requires a List or array, but expression type is " + ownerType);
    }
    List<? extends TypeMirror> arguments = listType.getTypeArguments();
    if (arguments.size() != 1 || isUnbounded(arguments.get(0))) {
      return dynamic();
    }
    return known(readableType(arguments.get(0)));
  }

  private @Nullable DeclaredType findSupertype(
      TypeMirror candidate, String qualifiedName, Set<String> visited) {
    TypeMirror normalized = readableType(candidate);
    if (normalized.getKind() != TypeKind.DECLARED || !visited.add(normalized.toString())) {
      return null;
    }
    DeclaredType declared = (DeclaredType) normalized;
    TypeElement element = (TypeElement) declared.asElement();
    if (element.getQualifiedName().contentEquals(qualifiedName)) {
      return declared;
    }
    for (TypeMirror parent : types.directSupertypes(declared)) {
      @Nullable DeclaredType match = findSupertype(parent, qualifiedName, visited);
      if (match != null) {
        return match;
      }
    }
    return null;
  }

  private boolean canCoerce(TypeMirror sourceType, TypeMirror targetType) {
    if (sourceType.getKind() == TypeKind.NULL) {
      return !targetType.getKind().isPrimitive();
    }
    if (sourceType.getKind() == TypeKind.VOID || targetType.getKind() == TypeKind.VOID) {
      return false;
    }
    TypeMirror boxedSource = boxed(sourceType);
    TypeMirror boxedTarget = boxed(targetType);
    if (types.isAssignable(boxedSource, boxedTarget)
        || types.isSameType(boxedTarget, type(STRING))) {
      return true;
    }
    if (isNumber(boxedSource) && isSupportedNumericTarget(boxedTarget)) {
      return true;
    }
    return types.isSameType(boxedSource, type(STRING))
        && types.isSameType(boxedTarget, type(CHARACTER));
  }

  private TypeMirror boxed(TypeMirror value) {
    return value.getKind().isPrimitive()
        ? types.boxedClass((PrimitiveType) value).asType()
        : readableType(value);
  }

  private TypeMirror readableType(TypeMirror value) {
    if (value.getKind() == TypeKind.TYPEVAR) {
      return readableType(((TypeVariable) value).getUpperBound());
    }
    if (value.getKind() == TypeKind.WILDCARD) {
      WildcardType wildcard = (WildcardType) value;
      @Nullable TypeMirror upperBound = wildcard.getExtendsBound();
      return upperBound == null ? type(OBJECT) : readableType(upperBound);
    }
    return value;
  }

  private boolean isUnbounded(TypeMirror value) {
    return value.getKind() == TypeKind.WILDCARD && ((WildcardType) value).getExtendsBound() == null;
  }

  private boolean isNumber(TypeMirror value) {
    return types.isAssignable(value, type(NUMBER));
  }

  private boolean isSupportedNumericTarget(TypeMirror value) {
    String name = value.toString();
    return name.equals(Byte.class.getName())
        || name.equals(Short.class.getName())
        || name.equals(Integer.class.getName())
        || name.equals(Long.class.getName())
        || name.equals(Float.class.getName())
        || name.equals(Double.class.getName())
        || name.equals(BigDecimal.class.getName());
  }

  private TypeMirror type(String qualifiedName) {
    return requireTypeElement(qualifiedName).asType();
  }

  private TypeElement requireTypeElement(String qualifiedName) {
    TypeElement element = elements.getTypeElement(qualifiedName);
    if (element == null) {
      throw new IllegalStateException("Required type is unavailable: " + qualifiedName);
    }
    return element;
  }

  private static InferredType known(TypeMirror type) {
    return new InferredType(type, false);
  }

  private static InferredType dynamic() {
    return new InferredType(null, true);
  }

  private static final class InferredType {
    private final @Nullable TypeMirror type;
    private final boolean dynamic;

    private InferredType(@Nullable TypeMirror type, boolean dynamic) {
      this.type = type;
      this.dynamic = dynamic;
    }
  }
}
