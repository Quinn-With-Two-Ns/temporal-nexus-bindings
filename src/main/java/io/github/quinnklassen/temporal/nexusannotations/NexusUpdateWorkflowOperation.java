package io.github.quinnklassen.temporal.nexusannotations;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an update handler for Nexus binding generation.
 *
 * <p>The processor rejects this annotation until asynchronous update support is available.
 */
@Documented
@Experimental
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface NexusUpdateWorkflowOperation {
  Class<?> service();

  String name();

  String workflowId();

  String[] arguments() default {};
}
