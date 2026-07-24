package io.github.quinnklassen.temporal.nexusannotations;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an activity handler for Nexus binding generation.
 *
 * <p>The processor rejects this annotation while the library targets Temporal Java SDK 1.37.0,
 * which does not expose the Nexus activity bridge needed for asynchronous completion.
 */
@Documented
@Experimental
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface NexusActivityOperation {
  Class<?> service();

  String name();

  String activityId();

  String[] arguments() default {};

  String scheduleToCloseTimeout() default "";

  String startToCloseTimeout() default "";
}
