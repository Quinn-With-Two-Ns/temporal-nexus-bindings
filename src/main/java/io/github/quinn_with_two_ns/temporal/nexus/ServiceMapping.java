package io.github.quinn_with_two_ns.temporal.nexus;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines the default typed Nexus service for operation mappings on a Temporal workflow or activity
 * implementation.
 *
 * <p>A service specified directly on an operation annotation takes precedence over this default.
 */
@Documented
@Experimental
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ServiceMapping {
  /**
   * The typed Nexus service to use by default for operation annotations in this implementation.
   *
   * <p>The selected interface must be annotated with {@code io.nexusrpc.Service}.
   *
   * @return the default typed Nexus service
   */
  Class<?> value();
}
