package io.github.quinn_with_two_ns.temporal.nexus.internal;

import io.nexusrpc.OperationDefinition;
import io.nexusrpc.ServiceDefinition;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
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
      WorkflowStartOptionsSpec options,
      String[] arguments,
      Class<?>[] parameterTypes) {
    Binding<I, R> binding =
        new Binding<>(service, operation, workflowId, options, arguments, parameterTypes);
    return TemporalOperationHandler.create(
        (context, client, input) ->
            client.startWorkflow(
                workflowType,
                binding.outputClass(),
                binding.outputType(),
                binding.workflowOptions(
                    context, input, Nexus.getOperationContext().getInfo().getTaskQueue()),
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
          Object[] evaluatedArguments = binding.arguments(context, input);
          try {
            stub.signal(signalName, evaluatedArguments);
          } catch (WorkflowNotFoundException e) {
            throw binding.workflowNotFound(e);
          }
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
          Object[] evaluatedArguments = binding.arguments(context, input);
          final R result;
          try {
            result =
                stub.query(
                    queryName, binding.outputClass(), binding.outputType(), evaluatedArguments);
          } catch (WorkflowNotFoundException e) {
            throw binding.workflowNotFound(e);
          }
          return TemporalOperationResult.sync(result);
        });
  }

  /** Expression sources emitted by the annotation processor for workflow start options. */
  public static final class WorkflowStartOptionsSpec {
    private static final WorkflowStartOptionsSpec EMPTY =
        new WorkflowStartOptionsSpec(
            "", "", "", "", "", "", "", "", "", "", new String[0], "", "", "", "", "", "");

    private final String taskQueue;
    private final String executionTimeout;
    private final String runTimeout;
    private final String taskTimeout;
    private final String workflowIdReusePolicy;
    private final String workflowIdConflictPolicy;
    private final String retryInitialInterval;
    private final String retryBackoffCoefficient;
    private final String retryMaximumAttempts;
    private final String retryMaximumInterval;
    private final String[] retryDoNotRetry;
    private final String startDelay;
    private final String priorityKey;
    private final String priorityFairnessKey;
    private final String priorityFairnessWeight;
    private final String summary;
    private final String details;

    public WorkflowStartOptionsSpec(
        String taskQueue,
        String executionTimeout,
        String runTimeout,
        String taskTimeout,
        String workflowIdReusePolicy,
        String workflowIdConflictPolicy,
        String retryInitialInterval,
        String retryBackoffCoefficient,
        String retryMaximumAttempts,
        String retryMaximumInterval,
        String[] retryDoNotRetry,
        String startDelay,
        String priorityKey,
        String priorityFairnessKey,
        String priorityFairnessWeight,
        String summary,
        String details) {
      this.taskQueue = taskQueue;
      this.executionTimeout = executionTimeout;
      this.runTimeout = runTimeout;
      this.taskTimeout = taskTimeout;
      this.workflowIdReusePolicy = workflowIdReusePolicy;
      this.workflowIdConflictPolicy = workflowIdConflictPolicy;
      this.retryInitialInterval = retryInitialInterval;
      this.retryBackoffCoefficient = retryBackoffCoefficient;
      this.retryMaximumAttempts = retryMaximumAttempts;
      this.retryMaximumInterval = retryMaximumInterval;
      this.retryDoNotRetry = retryDoNotRetry.clone();
      this.startDelay = startDelay;
      this.priorityKey = priorityKey;
      this.priorityFairnessKey = priorityFairnessKey;
      this.priorityFairnessWeight = priorityFairnessWeight;
      this.summary = summary;
      this.details = details;
    }
  }

  private static final class WorkflowStartOptionsBinding {
    private final @Nullable InputExpression taskQueue;
    private final @Nullable InputExpression executionTimeout;
    private final @Nullable InputExpression runTimeout;
    private final @Nullable InputExpression taskTimeout;
    private final @Nullable InputExpression workflowIdReusePolicy;
    private final @Nullable InputExpression workflowIdConflictPolicy;
    private final @Nullable InputExpression retryInitialInterval;
    private final @Nullable InputExpression retryBackoffCoefficient;
    private final @Nullable InputExpression retryMaximumAttempts;
    private final @Nullable InputExpression retryMaximumInterval;
    private final InputExpression[] retryDoNotRetry;
    private final @Nullable InputExpression startDelay;
    private final @Nullable InputExpression priorityKey;
    private final @Nullable InputExpression priorityFairnessKey;
    private final @Nullable InputExpression priorityFairnessWeight;
    private final @Nullable InputExpression summary;
    private final @Nullable InputExpression details;

    private WorkflowStartOptionsBinding(WorkflowStartOptionsSpec options) {
      this.taskQueue = optional(options.taskQueue);
      this.executionTimeout = optional(options.executionTimeout);
      this.runTimeout = optional(options.runTimeout);
      this.taskTimeout = optional(options.taskTimeout);
      this.workflowIdReusePolicy = optional(options.workflowIdReusePolicy);
      this.workflowIdConflictPolicy = optional(options.workflowIdConflictPolicy);
      this.retryInitialInterval = optional(options.retryInitialInterval);
      this.retryBackoffCoefficient = optional(options.retryBackoffCoefficient);
      this.retryMaximumAttempts = optional(options.retryMaximumAttempts);
      this.retryMaximumInterval = optional(options.retryMaximumInterval);
      this.retryDoNotRetry = compile(options.retryDoNotRetry);
      this.startDelay = optional(options.startDelay);
      this.priorityKey = optional(options.priorityKey);
      this.priorityFairnessKey = optional(options.priorityFairnessKey);
      this.priorityFairnessWeight = optional(options.priorityFairnessWeight);
      this.summary = optional(options.summary);
      this.details = optional(options.details);
    }

    private <I> void apply(
        WorkflowOptions.Builder builder,
        TemporalOperationStartContext context,
        I input,
        String defaultTaskQueue) {
      @Nullable String evaluatedTaskQueue = value(taskQueue, context, input, "options.taskQueue");
      builder.setTaskQueue(evaluatedTaskQueue == null ? defaultTaskQueue : evaluatedTaskQueue);

      @Nullable String execution =
          value(executionTimeout, context, input, "options.executionTimeout");
      if (execution != null) {
        builder.setWorkflowExecutionTimeout(
            WorkflowStartOptionParsers.positiveDuration("options.executionTimeout", execution));
      }
      @Nullable String run = value(runTimeout, context, input, "options.runTimeout");
      if (run != null) {
        builder.setWorkflowRunTimeout(
            WorkflowStartOptionParsers.positiveDuration("options.runTimeout", run));
      }
      @Nullable String task = value(taskTimeout, context, input, "options.taskTimeout");
      if (task != null) {
        builder.setWorkflowTaskTimeout(
            WorkflowStartOptionParsers.positiveDuration("options.taskTimeout", task));
      }

      @Nullable String reuse =
          value(workflowIdReusePolicy, context, input, "options.workflowIdReusePolicy");
      WorkflowIdReusePolicy reusePolicy =
          reuse == null
              ? WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_UNSPECIFIED
              : WorkflowStartOptionParsers.workflowIdReusePolicy(
                  "options.workflowIdReusePolicy", reuse);
      if (reuse != null) {
        builder.setWorkflowIdReusePolicy(reusePolicy);
      }
      @Nullable String conflict =
          value(workflowIdConflictPolicy, context, input, "options.workflowIdConflictPolicy");
      WorkflowIdConflictPolicy conflictPolicy =
          conflict == null
              ? WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_UNSPECIFIED
              : WorkflowStartOptionParsers.workflowIdConflictPolicy(
                  "options.workflowIdConflictPolicy", conflict);
      if (conflict != null) {
        builder.setWorkflowIdConflictPolicy(conflictPolicy);
      }
      if (reusePolicy == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING
          && conflictPolicy != WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_UNSPECIFIED) {
        throw new IllegalArgumentException(
            "options.workflowIdConflictPolicy cannot be used with"
                + " workflowIdReusePolicy TERMINATE_IF_RUNNING");
      }

      applyRetry(builder, context, input);
      @Nullable String delay = value(startDelay, context, input, "options.startDelay");
      if (delay != null) {
        builder.setStartDelay(
            WorkflowStartOptionParsers.nonNegativeDuration("options.startDelay", delay));
      }
      applyPriority(builder, context, input);

      @Nullable String evaluatedSummary = value(summary, context, input, "options.summary");
      if (evaluatedSummary != null) {
        builder.setStaticSummary(evaluatedSummary);
      }
      @Nullable String evaluatedDetails = value(details, context, input, "options.details");
      if (evaluatedDetails != null) {
        builder.setStaticDetails(evaluatedDetails);
      }
    }

    private <I> void applyRetry(
        WorkflowOptions.Builder builder, TemporalOperationStartContext context, I input) {
      if (retryInitialInterval == null
          && retryBackoffCoefficient == null
          && retryMaximumAttempts == null
          && retryMaximumInterval == null
          && retryDoNotRetry.length == 0) {
        return;
      }
      RetryOptions.Builder retry = RetryOptions.newBuilder();
      @Nullable String initial =
          value(retryInitialInterval, context, input, "options.retryInitialInterval");
      if (initial != null) {
        retry.setInitialInterval(
            WorkflowStartOptionParsers.positiveDuration("options.retryInitialInterval", initial));
      }
      @Nullable String coefficient =
          value(retryBackoffCoefficient, context, input, "options.retryBackoffCoefficient");
      if (coefficient != null) {
        retry.setBackoffCoefficient(
            WorkflowStartOptionParsers.backoffCoefficient(
                "options.retryBackoffCoefficient", coefficient));
      }
      @Nullable String attempts =
          value(retryMaximumAttempts, context, input, "options.retryMaximumAttempts");
      if (attempts != null) {
        retry.setMaximumAttempts(
            WorkflowStartOptionParsers.nonNegativeInteger(
                "options.retryMaximumAttempts", attempts));
      }
      @Nullable String maximum =
          value(retryMaximumInterval, context, input, "options.retryMaximumInterval");
      if (maximum != null) {
        retry.setMaximumInterval(
            WorkflowStartOptionParsers.positiveDuration("options.retryMaximumInterval", maximum));
      }
      if (retryDoNotRetry.length > 0) {
        String[] values = new String[retryDoNotRetry.length];
        for (int i = 0; i < retryDoNotRetry.length; i++) {
          values[i] =
              requiredValue(
                  retryDoNotRetry[i], context, input, "options.retryDoNotRetry[" + i + "]");
        }
        retry.setDoNotRetry(values);
      }
      builder.setRetryOptions(retry.validateBuildWithDefaults());
    }

    private <I> void applyPriority(
        WorkflowOptions.Builder builder, TemporalOperationStartContext context, I input) {
      if (priorityKey == null && priorityFairnessKey == null && priorityFairnessWeight == null) {
        return;
      }
      Priority.Builder priority = Priority.newBuilder();
      @Nullable String key = value(priorityKey, context, input, "options.priorityKey");
      if (key != null) {
        priority.setPriorityKey(
            WorkflowStartOptionParsers.nonNegativeInteger("options.priorityKey", key));
      }
      @Nullable String fairnessKey =
          value(priorityFairnessKey, context, input, "options.priorityFairnessKey");
      if (fairnessKey != null) {
        priority.setFairnessKey(fairnessKey);
      }
      @Nullable String fairnessWeight =
          value(priorityFairnessWeight, context, input, "options.priorityFairnessWeight");
      if (fairnessWeight != null) {
        priority.setFairnessWeight(
            WorkflowStartOptionParsers.nonNegativeFloat(
                "options.priorityFairnessWeight", fairnessWeight));
      }
      builder.setPriority(priority.build());
    }

    private static @Nullable InputExpression optional(String source) {
      return source.isEmpty() ? null : InputExpression.compile(source);
    }

    private static InputExpression[] compile(String[] sources) {
      InputExpression[] result = new InputExpression[sources.length];
      for (int i = 0; i < sources.length; i++) {
        result[i] = InputExpression.compile(sources[i]);
      }
      return result;
    }

    private static <I> @Nullable String value(
        @Nullable InputExpression expression,
        TemporalOperationStartContext context,
        I input,
        String attribute) {
      return expression == null ? null : requiredValue(expression, context, input, attribute);
    }

    private static <I> String requiredValue(
        InputExpression expression,
        TemporalOperationStartContext context,
        I input,
        String attribute) {
      String result =
          (String)
              expression.evaluate(
                  input, context.getRequestId(), context.getHeaders(), String.class);
      if (result == null || result.trim().isEmpty()) {
        throw new IllegalArgumentException(attribute + " evaluated to null or empty");
      }
      return result;
    }
  }

  private static final class Binding<I, R> {
    private final OperationDefinition definition;
    private final InputExpression id;
    private final WorkflowStartOptionsBinding startOptions;
    private final InputExpression[] arguments;
    private final Class<?>[] parameterTypes;
    private final boolean directInput;

    private Binding(
        Class<?> service,
        String operation,
        String id,
        String[] arguments,
        Class<?>[] parameterTypes) {
      this(service, operation, id, WorkflowStartOptionsSpec.EMPTY, arguments, parameterTypes);
    }

    private Binding(
        Class<?> service,
        String operation,
        String id,
        WorkflowStartOptionsSpec startOptions,
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
      this.startOptions = new WorkflowStartOptionsBinding(startOptions);
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

    private WorkflowOptions workflowOptions(
        TemporalOperationStartContext context, I input, String defaultTaskQueue) {
      try {
        WorkflowOptions.Builder builder =
            WorkflowOptions.newBuilder().setWorkflowId(id(context, input, "workflowId"));
        startOptions.apply(builder, context, input, defaultTaskQueue);
        return builder.build();
      } catch (IllegalArgumentException e) {
        throw badRequest(e);
      }
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

    private HandlerException workflowNotFound(WorkflowNotFoundException cause) {
      return new HandlerException(
          HandlerException.ErrorType.NOT_FOUND,
          "No workflow execution found for " + definition.getName() + ": " + cause.getMessage(),
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
