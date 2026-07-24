package io.github.quinn_with_two_ns.temporal.nexus.internal;

import io.nexusrpc.OperationDefinition;
import io.nexusrpc.ServiceDefinition;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.TemporalOperationResult;
import io.temporal.nexus.TemporalOperationStartContext;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;

/** Runtime handler factories called by annotation-processor-generated Nexus service bindings. */
@Experimental
public final class GeneratedNexusOperationHandlers {
  private GeneratedNexusOperationHandlers() {}

  public static <I, R> OperationHandler<I, R> workflow(
      Class<?> service,
      String operation,
      String workflowType,
      String workflowId,
      String taskQueue,
      String[] arguments,
      Class<?>[] parameterTypes) {
    Binding<I, R> binding =
        new Binding<>(service, operation, workflowId, taskQueue, arguments, parameterTypes);
    return TemporalOperationHandler.create(
        (context, client, input) ->
            client.startWorkflow(
                workflowType,
                binding.outputClass(),
                binding.outputType(),
                WorkflowOptions.newBuilder()
                    .setWorkflowId(binding.id(context, input, "workflowId"))
                    .setTaskQueue(
                        binding.taskQueue(
                            context, input, Nexus.getOperationContext().getInfo().getTaskQueue()))
                    .build(),
                binding.arguments(context, input)));
  }

  public static <I> OperationHandler<I, Void> signal(
      Class<?> service,
      String operation,
      String signalName,
      String workflowId,
      String[] arguments,
      Class<?>[] parameterTypes) {
    Binding<I, Void> binding =
        new Binding<>(service, operation, workflowId, arguments, parameterTypes);
    return TemporalOperationHandler.create(
        (context, client, input) -> {
          WorkflowStub stub =
              client
                  .getWorkflowClient()
                  .newUntypedWorkflowStub(binding.id(context, input, "workflowId"));
          stub.signal(signalName, binding.arguments(context, input));
          return TemporalOperationResult.sync(null);
        });
  }

  public static <I, R> OperationHandler<I, R> query(
      Class<?> service,
      String operation,
      String queryName,
      String workflowId,
      String[] arguments,
      Class<?>[] parameterTypes) {
    Binding<I, R> binding =
        new Binding<>(service, operation, workflowId, arguments, parameterTypes);
    return TemporalOperationHandler.create(
        (context, client, input) -> {
          WorkflowStub stub =
              client
                  .getWorkflowClient()
                  .newUntypedWorkflowStub(binding.id(context, input, "workflowId"));
          R result =
              stub.query(
                  queryName,
                  binding.outputClass(),
                  binding.outputType(),
                  binding.arguments(context, input));
          return TemporalOperationResult.sync(result);
        });
  }

  private static final class Binding<I, R> {
    private final OperationDefinition definition;
    private final InputExpression id;
    private final @Nullable InputExpression taskQueue;
    private final InputExpression[] arguments;
    private final Class<?>[] parameterTypes;
    private final boolean directInput;

    private Binding(
        Class<?> service,
        String operation,
        String id,
        String[] arguments,
        Class<?>[] parameterTypes) {
      this(service, operation, id, "", arguments, parameterTypes);
    }

    private Binding(
        Class<?> service,
        String operation,
        String id,
        String taskQueue,
        String[] arguments,
        Class<?>[] parameterTypes) {
      @Nullable OperationDefinition operationDefinition =
          ServiceDefinition.fromClass(service).getOperations().get(operation);
      if (operationDefinition == null) {
        throw new IllegalArgumentException(
            "Nexus service " + service.getName() + " has no operation named " + operation);
      }
      this.definition = operationDefinition;
      this.id = InputExpression.compile(id);
      this.taskQueue = taskQueue.isEmpty() ? null : InputExpression.compile(taskQueue);
      this.parameterTypes = parameterTypes.clone();
      this.directInput = arguments.length == 0 && parameterTypes.length == 1;
      if (!directInput && arguments.length != parameterTypes.length) {
        throw new IllegalArgumentException(
            "argument expression count does not match Temporal parameter count");
      }
      this.arguments = new InputExpression[arguments.length];
      for (int i = 0; i < arguments.length; i++) {
        this.arguments[i] = InputExpression.compile(arguments[i]);
      }
    }

    private String taskQueue(TemporalOperationStartContext context, I input, String fallback) {
      if (taskQueue == null) {
        return fallback;
      }
      return requiredString(taskQueue, context, input, "taskQueue");
    }

    private String id(TemporalOperationStartContext context, I input, String attribute) {
      return requiredString(id, context, input, attribute);
    }

    private String requiredString(
        InputExpression expression,
        TemporalOperationStartContext context,
        I input,
        String attribute) {
      try {
        String result =
            (String)
                expression.evaluate(
                    input, context.getRequestId(), context.getHeaders(), String.class);
        if (result == null || result.trim().isEmpty()) {
          throw new IllegalArgumentException(attribute + " evaluated to null or empty");
        }
        return result;
      } catch (IllegalArgumentException e) {
        throw badRequest(e);
      }
    }

    private Object[] arguments(TemporalOperationStartContext context, I input) {
      try {
        if (directInput) {
          return new Object[] {InputExpression.coerce(input, parameterTypes[0], "#{input}")};
        }
        Object[] result = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
          result[i] =
              arguments[i].evaluate(
                  input, context.getRequestId(), context.getHeaders(), parameterTypes[i]);
        }
        return result;
      } catch (IllegalArgumentException e) {
        throw badRequest(e);
      }
    }

    @SuppressWarnings("unchecked")
    private Class<R> outputClass() {
      Type output = outputType();
      Class<?> raw =
          output instanceof Class
              ? (Class<?>) output
              : (Class<?>) ((ParameterizedType) output).getRawType();
      if (raw == void.class) {
        raw = Void.class;
      }
      return (Class<R>) box(raw);
    }

    private Type outputType() {
      return definition.getOutputType();
    }

    private HandlerException badRequest(IllegalArgumentException cause) {
      return new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          "Invalid Nexus input for " + definition.getName() + ": " + cause.getMessage(),
          cause,
          HandlerException.RetryBehavior.NON_RETRYABLE);
    }
  }

  private static Class<?> box(Class<?> type) {
    if (!type.isPrimitive()) return type;
    if (type == boolean.class) return Boolean.class;
    if (type == byte.class) return Byte.class;
    if (type == short.class) return Short.class;
    if (type == int.class) return Integer.class;
    if (type == long.class) return Long.class;
    if (type == float.class) return Float.class;
    if (type == double.class) return Double.class;
    if (type == char.class) return Character.class;
    return type;
  }
}
