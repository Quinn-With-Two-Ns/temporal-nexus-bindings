package io.github.quinn_with_two_ns.temporal.nexus;

import io.temporal.common.Experimental;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that contributes handwritten Nexus operation handlers to an otherwise generated
 * Nexus service binding.
 *
 * <p>A fragment declares ordinary Nexus SDK {@code io.nexusrpc.handler.OperationImpl} factory
 * methods. Each method must be public, non-static, non-generic, parameterless, declare no checked
 * exceptions, return {@code io.nexusrpc.handler.OperationHandler}, and be named after an operation
 * method of the selected typed Nexus service.
 *
 * <p>Fragments are not Nexus service implementations and cannot be registered on a worker
 * themselves. The processor composes every fragment method and every annotated operation mapping
 * into the single generated binding for the service, which then requires an instance of each
 * fragment:
 *
 * <pre>{@code
 * DeploymentServiceNexusBindings.register(worker, new ReportsFragment(metricsClient));
 * }</pre>
 *
 * <p>Every operation of the service must be provided exactly once, by either a fragment method or
 * an operation mapping.
 */
@Documented
@Experimental
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface NexusServiceFragment {
  /**
   * The typed Nexus service this fragment contributes handwritten operation handlers to.
   *
   * <p>The selected interface must be annotated with {@code io.nexusrpc.Service}.
   *
   * @return the typed Nexus service completed by this fragment
   */
  Class<?> service();
}
