package io.github.quinn_with_two_ns.temporal.nexus;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the Temporal options used to start a workflow-backed Nexus operation.
 *
 * <p>Every string accepts the same bounded input expressions as {@link WorkflowOperation}. An empty
 * value leaves the corresponding Temporal option unset. Duration values use ISO-8601 syntax, such
 * as {@code PT30S} or {@code PT2H}.
 */
@Documented
@Experimental
@Retention(RetentionPolicy.SOURCE)
@Target({})
public @interface WorkflowStartOptions {
  /**
   * The workflow task queue. An empty value uses the task queue of the Nexus worker handling the
   * operation.
   */
  String taskQueue() default "";

  /**
   * The timeout for the complete workflow execution, including retries and continue-as-new runs.
   */
  String executionTimeout() default "";

  /** The timeout for a single workflow run. */
  String runTimeout() default "";

  /** The timeout for a single workflow task. */
  String taskTimeout() default "";

  /**
   * The workflow ID reuse policy. Accepts a Temporal enum name or its shortened suffix, such as
   * {@code REJECT_DUPLICATE}.
   */
  String workflowIdReusePolicy() default "";

  /**
   * The workflow ID conflict policy. Accepts a Temporal enum name or its shortened suffix, such as
   * {@code USE_EXISTING}.
   */
  String workflowIdConflictPolicy() default "";

  /** The initial interval for workflow retries. */
  String retryInitialInterval() default "";

  /** The workflow retry backoff coefficient. */
  String retryBackoffCoefficient() default "";

  /** The maximum number of workflow execution attempts. Zero means unlimited. */
  String retryMaximumAttempts() default "";

  /** The maximum interval between workflow retries. */
  String retryMaximumInterval() default "";

  /**
   * Application failure types that should not be retried. Each entry may be an input expression.
   */
  String[] retryDoNotRetry() default {};

  /** The delay before dispatching the first workflow task. */
  String startDelay() default "";

  /** The workflow priority key. */
  String priorityKey() default "";

  /** The fairness key used for workflow priority balancing. */
  String priorityFairnessKey() default "";

  /** The fairness weight used for workflow priority balancing. */
  String priorityFairnessWeight() default "";

  /** A single-line summary stored on the workflow execution. */
  String summary() default "";

  /** Detailed Temporal Markdown stored on the workflow execution. */
  String details() default "";
}
