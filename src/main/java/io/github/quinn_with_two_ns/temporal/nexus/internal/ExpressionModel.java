package io.github.quinn_with_two_ns.temporal.nexus.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Parsed expression model shared by runtime evaluation and compile-time validation. */
final class ExpressionModel {
  final String source;
  final List<Segment> segments;
  final boolean singleExpression;

  static ExpressionModel parse(@Nullable String source) {
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
    return new ExpressionModel(source, segments, singleExpression);
  }

  private ExpressionModel(String source, List<Segment> segments, boolean singleExpression) {
    this.source = source;
    this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    this.singleExpression = singleExpression;
  }

  private static Segment parseBody(String body) {
    if ("payload".equals(body) || "input".equals(body)) {
      return PayloadSegment.INSTANCE;
    }
    if ("null".equals(body)) return new ConstantSegment(null);
    if ("true".equals(body)) return new ConstantSegment(true);
    if ("false".equals(body)) return new ConstantSegment(false);
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
          throw new IllegalArgumentException(
              "unterminated container access in \"#{" + body + "}\"");
        }
        String keyOrIndex = body.substring(position + 1, closing).trim();
        if (isQuoted(keyOrIndex)) {
          steps.add(new MapKeyStep(unquote(keyOrIndex)));
        } else if (keyOrIndex.matches("[0-9]+")) {
          steps.add(new IndexStep(parseIndex(keyOrIndex)));
        } else {
          throw new IllegalArgumentException(
              "container access requires a quoted map key or non-negative integer index in \"#{"
                  + body
                  + "}\"");
        }
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
    Root root = Root.PAYLOAD;
    if (!steps.isEmpty() && steps.get(0) instanceof PropertyStep) {
      String firstProperty = ((PropertyStep) steps.get(0)).property;
      if ("input".equals(firstProperty) || "payload".equals(firstProperty)) {
        steps.remove(0);
      } else if ("nexus".equals(firstProperty)) {
        steps.remove(0);
        if (steps.isEmpty() || !(steps.get(0) instanceof PropertyStep)) {
          throw new IllegalArgumentException(
              "nexus metadata access requires requestId or headers in \"#{" + body + "}\"");
        }
        String metadataProperty = ((PropertyStep) steps.remove(0)).property;
        if ("requestId".equals(metadataProperty)) {
          root = Root.REQUEST_ID;
        } else if ("headers".equals(metadataProperty)) {
          root = Root.HEADERS;
        } else {
          throw new IllegalArgumentException(
              "unsupported Nexus metadata property \"" + metadataProperty + "\"");
        }
      }
    }
    return new PathSegment(root, steps);
  }

  private static int parseIndex(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("container index is out of range: " + value, e);
    }
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

  interface Segment {}

  static final class LiteralSegment implements Segment {
    final String value;

    LiteralSegment(String value) {
      this.value = value;
    }
  }

  static final class ConstantSegment implements Segment {
    final @Nullable Object value;

    ConstantSegment(@Nullable Object value) {
      this.value = value;
    }
  }

  static final class PayloadSegment implements Segment {
    static final PayloadSegment INSTANCE = new PayloadSegment();

    private PayloadSegment() {}
  }

  static final class PathSegment implements Segment {
    final Root root;
    final List<PathStep> steps;

    PathSegment(Root root, List<PathStep> steps) {
      this.root = root;
      this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }
  }

  enum Root {
    PAYLOAD,
    REQUEST_ID,
    HEADERS
  }

  interface PathStep {}

  static final class MapKeyStep implements PathStep {
    final String key;

    MapKeyStep(String key) {
      this.key = key;
    }
  }

  static final class IndexStep implements PathStep {
    final int index;

    IndexStep(int index) {
      this.index = index;
    }
  }

  static final class PropertyStep implements PathStep {
    final String property;

    PropertyStep(String property) {
      this.property = property;
    }
  }
}
