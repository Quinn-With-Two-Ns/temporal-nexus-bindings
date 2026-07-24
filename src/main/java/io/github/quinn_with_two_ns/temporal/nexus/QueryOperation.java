package io.github.quinn_with_two_ns.temporal.nexus;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a synchronous Nexus operation backed by a registered workflow query handler.
 *
 * <p>Expressions read Nexus payload properties by default. The {@code nexus} root exposes request
 * metadata; for example, {@code #{nexus.requestId}} and {@code #{nexus.headers['x-routing-key']}}.
 * Use {@code payload} or {@code input} to address the operation payload explicitly, such as {@code
 * #{payload.id}}.
 */
@Documented
@Experimental
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface QueryOperation {
  /**
   * The typed Nexus service that declares the operation.
   *
   * <p>Leave this as {@code void.class} to use the service declared by {@link ServiceMapping} on
   * the enclosing Temporal implementation class.
   *
   * @return the typed Nexus service, or {@code void.class} to use the enclosing mapping
   */
  Class<?> service() default void.class;

  /**
   * The name of the operation in the typed Nexus service.
   *
   * @return the name declared by the service's {@code @Operation}
   */
  String name();

  /**
   * The ID of the workflow to query.
   *
   * <p>The value may be a literal, an input expression, or a template containing input expressions.
   * It must evaluate to a non-empty string.
   *
   * @return the workflow ID expression
   */
  String workflowId();

  /**
   * Expressions that produce the Temporal query method arguments, in parameter order.
   *
   * <p>The number of expressions must match the number of Temporal method parameters. As a
   * convenience, an empty array passes the complete Nexus input directly when the Temporal method
   * has one compatible parameter. An empty array also represents no arguments for a no-argument
   * method.
   *
   * @return the query argument expressions
   */
  String[] arguments() default {};
}
