package io.github.quinnklassen.temporal.nexusannotations;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates an asynchronous Nexus operation backed by a registered workflow entry method. */
@Documented
@Experimental
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface NexusWorkflowOperation {
  Class<?> service();

  String name();

  String workflowId();

  String[] arguments() default {};
}
