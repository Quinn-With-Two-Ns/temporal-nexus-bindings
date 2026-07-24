package io.github.quinnklassen.temporal.nexusannotations;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates a synchronous Nexus operation backed by a registered workflow signal handler. */
@Documented
@Experimental
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface NexusSignalWorkflowOperation {
  Class<?> service();

  String name();

  String workflowId();

  String[] arguments() default {};
}
