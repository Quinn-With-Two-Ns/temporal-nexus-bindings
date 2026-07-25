package io.github.quinn_with_two_ns.temporal.nexus.internal;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.time.Duration;
import java.util.Locale;

/** Parses expression results into the scalar types accepted by Temporal workflow start options. */
final class WorkflowStartOptionParsers {
  private static final String REUSE_PREFIX = "WORKFLOW_ID_REUSE_POLICY_";
  private static final String CONFLICT_PREFIX = "WORKFLOW_ID_CONFLICT_POLICY_";

  private WorkflowStartOptionParsers() {}

  static Duration positiveDuration(String attribute, String value) {
    Duration result = duration(attribute, value);
    if (result.isZero() || result.isNegative()) {
      throw invalid(attribute, "must be greater than zero");
    }
    return result;
  }

  static Duration nonNegativeDuration(String attribute, String value) {
    Duration result = duration(attribute, value);
    if (result.isNegative()) {
      throw invalid(attribute, "must not be negative");
    }
    return result;
  }

  static int nonNegativeInteger(String attribute, String value) {
    final int result;
    try {
      result = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw invalid(attribute, "must be an integer", e);
    }
    if (result < 0) {
      throw invalid(attribute, "must not be negative");
    }
    return result;
  }

  static double backoffCoefficient(String attribute, String value) {
    final double result;
    try {
      result = Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw invalid(attribute, "must be a number", e);
    }
    if (!Double.isFinite(result) || result < 1.0) {
      throw invalid(attribute, "must be a finite number greater than or equal to 1.0");
    }
    return result;
  }

  static float nonNegativeFloat(String attribute, String value) {
    final float result;
    try {
      result = Float.parseFloat(value);
    } catch (NumberFormatException e) {
      throw invalid(attribute, "must be a number", e);
    }
    if (!Float.isFinite(result) || result < 0.0f) {
      throw invalid(attribute, "must be a finite, non-negative number");
    }
    return result;
  }

  static WorkflowIdReusePolicy workflowIdReusePolicy(String attribute, String value) {
    String normalized = normalize(value);
    if (!normalized.startsWith(REUSE_PREFIX)) {
      normalized = REUSE_PREFIX + normalized;
    }
    try {
      return WorkflowIdReusePolicy.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw invalid(attribute, "is not a recognized workflow ID reuse policy", e);
    }
  }

  static WorkflowIdConflictPolicy workflowIdConflictPolicy(String attribute, String value) {
    String normalized = normalize(value);
    if (!normalized.startsWith(CONFLICT_PREFIX)) {
      normalized = CONFLICT_PREFIX + normalized;
    }
    try {
      return WorkflowIdConflictPolicy.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw invalid(attribute, "is not a recognized workflow ID conflict policy", e);
    }
  }

  private static Duration duration(String attribute, String value) {
    try {
      return Duration.parse(value);
    } catch (RuntimeException e) {
      throw invalid(attribute, "must be an ISO-8601 duration", e);
    }
  }

  private static String normalize(String value) {
    return value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static IllegalArgumentException invalid(String attribute, String message) {
    return new IllegalArgumentException(attribute + " " + message);
  }

  private static IllegalArgumentException invalid(
      String attribute, String message, RuntimeException cause) {
    return new IllegalArgumentException(attribute + " " + message, cause);
  }
}
