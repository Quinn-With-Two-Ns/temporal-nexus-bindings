package io.github.quinn_with_two_ns.temporal.nexus;

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
public @interface ActivityOperation {
  /**
   * The typed Nexus service that declares the operation.
   *
   * <p>Leave this as {@code void.class} to use the service declared by {@link ServiceMapping} on
   * the enclosing Temporal implementation class. This setting is reserved until activity mappings
   * are supported.
   *
   * @return the typed Nexus service, or {@code void.class} to use the enclosing mapping
   */
  Class<?> service() default void.class;

  /**
   * The name of the operation in the typed Nexus service.
   *
   * <p>This setting is reserved until activity mappings are supported.
   *
   * @return the name declared by the service's {@code @Operation}
   */
  String name();

  /**
   * The activity ID to use when starting the activity.
   *
   * <p>The intended value is a literal, an input expression, or a template containing input
   * expressions. This setting is reserved until activity mappings are supported.
   *
   * @return the activity ID expression
   */
  String activityId();

  /**
   * Expressions intended to produce the Temporal activity method arguments, in parameter order.
   *
   * <p>This setting is reserved until activity mappings are supported.
   *
   * @return the activity argument expressions
   */
  String[] arguments() default {};

  /**
   * The intended schedule-to-close timeout for the activity.
   *
   * <p>An empty value leaves the timeout unspecified. This setting is reserved until activity
   * mappings are supported.
   *
   * @return the schedule-to-close timeout, or an empty string when unspecified
   */
  String scheduleToCloseTimeout() default "";

  /**
   * The intended start-to-close timeout for each activity attempt.
   *
   * <p>An empty value leaves the timeout unspecified. This setting is reserved until activity
   * mappings are supported.
   *
   * @return the start-to-close timeout, or an empty string when unspecified
   */
  String startToCloseTimeout() default "";
}
