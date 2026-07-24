package io.github.quinn_with_two_ns.temporal.nexus;

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
public @interface UpdateOperation {
  /**
   * The typed Nexus service that declares the operation.
   *
   * <p>Leave this as {@code void.class} to use the service declared by {@link ServiceMapping} on
   * the enclosing Temporal implementation class. This setting is reserved until update mappings are
   * supported.
   *
   * @return the typed Nexus service, or {@code void.class} to use the enclosing mapping
   */
  Class<?> service() default void.class;

  /**
   * The name of the operation in the typed Nexus service.
   *
   * <p>This setting is reserved until update mappings are supported.
   *
   * @return the name declared by the service's {@code @Operation}
   */
  String name();

  /**
   * The ID of the workflow to update.
   *
   * <p>The intended value is a literal, an input expression, or a template containing input
   * expressions. This setting is reserved until update mappings are supported.
   *
   * @return the workflow ID expression
   */
  String workflowId();

  /**
   * Expressions intended to produce the Temporal update method arguments, in parameter order.
   *
   * <p>This setting is reserved until update mappings are supported.
   *
   * @return the update argument expressions
   */
  String[] arguments() default {};
}
