# Temporal Nexus Bindings

An experimental, community-maintained annotation processor that exposes existing Temporal Java
workflow handlers as typed Nexus operations.

This project is independent of Temporal Technologies and is not an official Temporal SDK module.

## Requirements

- Java 8 or newer.
- Temporal Java SDK 1.37.0 or a compatible newer version.
- A typed Nexus `@Service` interface.
- All mappings for one service must be compiled together.

## Gradle

Groovy DSL:

```groovy
dependencies {
    implementation "io.github.quinn-with-two-ns:temporal-nexus-bindings:0.1.0"
    annotationProcessor "io.github.quinn-with-two-ns:temporal-nexus-bindings:0.1.0"
}
```

Kotlin DSL:

```kotlin
dependencies {
    implementation("io.github.quinn-with-two-ns:temporal-nexus-bindings:0.1.0")
    annotationProcessor("io.github.quinn-with-two-ns:temporal-nexus-bindings:0.1.0")
}
```

The library declares its compatible Temporal SDK version transitively. An application's direct
Temporal SDK dependency can select a newer compatible version.

## Maven

```xml
<dependency>
  <groupId>io.github.quinn-with-two-ns</groupId>
  <artifactId>temporal-nexus-bindings</artifactId>
  <version>0.1.0</version>
</dependency>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.14.0</version>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>io.github.quinn-with-two-ns</groupId>
            <artifactId>temporal-nexus-bindings</artifactId>
            <version>0.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Usage

Define an ordinary typed Nexus service:

```java
import io.nexusrpc.Operation;
import io.nexusrpc.Service;

public final class StartDeploymentInput {
  private String id;
  private String artifact;

  public StartDeploymentInput() {}

  public StartDeploymentInput(String id, String artifact) {
    this.id = id;
    this.artifact = artifact;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getArtifact() {
    return artifact;
  }

  public void setArtifact(String artifact) {
    this.artifact = artifact;
  }
}

public final class CancelDeploymentInput {
  private String id;
  private String reason;

  public CancelDeploymentInput() {}

  public CancelDeploymentInput(String id, String reason) {
    this.id = id;
    this.reason = reason;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}

public final class DeploymentResult {
  private String id;
  private String status;

  public DeploymentResult() {}

  public DeploymentResult(String id, String status) {
    this.id = id;
    this.status = status;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}

@Service
public interface DeploymentService {
  @Operation
  DeploymentResult start(StartDeploymentInput input);

  @Operation
  void cancel(CancelDeploymentInput input);
}
```

Map its operations onto normal Temporal implementation methods:

```java
import io.github.quinn_with_two_ns.temporal.nexus.SignalOperation;
import io.github.quinn_with_two_ns.temporal.nexus.ServiceMapping;
import io.github.quinn_with_two_ns.temporal.nexus.WorkflowOperation;

@WorkflowInterface
public interface DeploymentWorkflow {
  @WorkflowMethod
  DeploymentResult deploy(StartDeploymentInput input);

  @SignalMethod
  void cancel(String reason);
}

@ServiceMapping(DeploymentService.class)
public final class DeploymentWorkflowImpl implements DeploymentWorkflow {
  @Override
  @WorkflowOperation(
      name = "start",
      workflowId = "deployment-#{id}",
      options = @WorkflowStartOptions(taskQueue = "deployment-workers"))
  public DeploymentResult deploy(StartDeploymentInput input) {
    // Workflow implementation.
    throw new UnsupportedOperationException("Example only");
  }

  @Override
  @SignalOperation(
      name = "cancel",
      workflowId = "deployment-#{id}",
      arguments = "#{reason}")
  public void cancel(String reason) {
    // Signal implementation.
  }
}
```

`@ServiceMapping` supplies the default typed Nexus service for mappings on a Temporal workflow
or activity implementation class. A method-level `service` setting overrides the implementation
default. Because `deploy` has one compatible parameter and `arguments` is omitted, the complete
`StartDeploymentInput` POJO is passed to the workflow. The workflow's `DeploymentResult` POJO
becomes the Nexus operation result. The separate `CancelDeploymentInput` POJO supplies the workflow
ID and signal reason for `cancel`. Signal operations remain `void`, as Temporal signals do not
return values.

The processor generates one complete class named
`<TypedServiceSimpleName>NexusBindings` in the typed service's package. Register it explicitly:

```java
worker.registerWorkflowImplementationTypes(DeploymentWorkflowImpl.class);
DeploymentServiceNexusBindings.register(worker);
```

Explicit registration means generated services are not exposed merely because their annotations
were compiled. `register(worker)` returns the concrete generated binding. Use the typed
`DeploymentServiceNexusBindings.create()` method instead when registration needs to be handled
separately.

## Supported mappings

- `@WorkflowOperation` starts a workflow asynchronously.
- `@SignalOperation` signals an existing workflow synchronously.
- `@QueryOperation` queries an existing workflow synchronously.
- `@ActivityOperation` deliberately fails compilation while this library targets Temporal Java
  SDK 1.37.0, which does not expose the Nexus activity bridge needed for asynchronous completion.
- `@UpdateOperation` deliberately fails compilation until asynchronous update support
  is implemented.

Workflow starts use the Nexus worker's task queue by default. Set
`options = @WorkflowStartOptions(taskQueue = "...")` to route the workflow to another worker.
`WorkflowStartOptions` also configures workflow timeouts, ID policies, retries, start delay,
priority, summary, and details. Every option accepts a literal or the same input expressions used
by workflow IDs and arguments.

Expressions can also read Nexus request metadata:

- `#{nexus.requestId}` returns the caller-supplied Nexus request ID.
- `#{nexus.headers['x-routing-key']}` returns a Nexus request header. Header names are
  case-insensitive.
- `#{payload}` and `#{input}` return the complete operation payload. Use an explicit path such as
  `#{payload.requestId}` when desired; unqualified paths remain payload paths.

These roots are available in `workflowId`, `WorkflowStartOptions`, and `arguments`, including
inside templates such as `deployment-#{nexus.requestId}`. A missing header fails the Nexus call as
a bad request, just like other missing map keys. The `nexus` identifier is reserved; address a
payload property with that name explicitly as `#{payload.nexus}`.

Every operation in a referenced Nexus service must have exactly one annotated mapping. Missing,
duplicate, incompatible, or malformed mappings fail compilation.

## Annotation parameters

All operation annotations share these parameters:

| Parameter | Default | Description |
| --- | --- | --- |
| `service` | `void.class` | Typed Nexus `@Service` interface that declares the operation. The sentinel default inherits the service from `@ServiceMapping` on the enclosing Temporal implementation. Compilation fails if neither supplies a service. A method-level value takes precedence. |
| `name` | Required | Name declared by the typed service's `@Operation`. This is the logical Nexus operation name, not the Temporal workflow, signal, query, update, or activity name. |
| `arguments` | `{}` | Input expressions that produce Temporal method arguments in parameter order. Supply exactly one expression per Temporal parameter. For a compatible one-parameter method, the empty default passes the complete Nexus input directly; for a no-argument method, it supplies no arguments. |

The annotation-specific parameters are:

| Annotation | Parameter | Default | Description |
| --- | --- | --- | --- |
| `@ServiceMapping` | `value` | Required | Default typed Nexus `@Service` interface for operation annotations on the Temporal workflow or activity implementation class. |
| `@WorkflowOperation` | `workflowId` | Required | Literal or input-expression template that must produce a non-empty workflow ID. |
| `@WorkflowOperation` | `options` | `@WorkflowStartOptions` | Options used to start the workflow. Empty members retain Temporal SDK defaults. |
| `@SignalOperation` | `workflowId` | Required | Literal or input-expression template that must produce the non-empty ID of the workflow to signal. |
| `@QueryOperation` | `workflowId` | Required | Literal or input-expression template that must produce the non-empty ID of the workflow to query. |
| `@UpdateOperation` | `workflowId` | Required | Intended workflow ID expression for the workflow to update. Update mappings are currently rejected during compilation. |
| `@ActivityOperation` | `activityId` | Required | Intended activity ID expression. Activity mappings are currently rejected during compilation. |
| `@ActivityOperation` | `scheduleToCloseTimeout` | `""` | Intended schedule-to-close timeout; empty leaves it unspecified. Activity mappings are currently rejected during compilation. |
| `@ActivityOperation` | `startToCloseTimeout` | `""` | Intended start-to-close timeout for each attempt; empty leaves it unspecified. Activity mappings are currently rejected during compilation. |

`@WorkflowStartOptions` supports:

| Parameter | Value |
| --- | --- |
| `taskQueue` | Workflow task queue. Empty inherits the Nexus worker's task queue. |
| `executionTimeout`, `runTimeout`, `taskTimeout`, `startDelay` | ISO-8601 durations such as `PT30S` or expressions that produce them. |
| `workflowIdReusePolicy`, `workflowIdConflictPolicy` | Full Temporal enum names or short names such as `REJECT_DUPLICATE` and `FAIL`. |
| `retryInitialInterval`, `retryMaximumInterval` | ISO-8601 retry durations. |
| `retryBackoffCoefficient`, `retryMaximumAttempts` | Numeric retry settings represented as literals or expressions. |
| `retryDoNotRetry` | Failure type names; each array entry may be an expression. |
| `priorityKey`, `priorityFairnessKey`, `priorityFairnessWeight` | Temporal workflow priority settings. |
| `summary`, `details` | Temporal Markdown text; templates may include input and Nexus metadata expressions. |

An empty option is not applied. Invalid literals fail annotation processing. Invalid values
produced at runtime become non-retryable Nexus `BAD_REQUEST` errors.

`service`, `name`, and `arguments` also exist on the currently unsupported activity and update
annotations, but the processor rejects those mappings before using any of their parameters.

## Input expressions

The annotations use a deliberately small, input-only SpEL-like language:

- `#{input}` and `#{payload}` select the complete typed Nexus input.
- `#{property}` and `#{nested.property}` read JavaBean getters, public fields, or Java record
  components.
- `#{['key']}` reads a map key.
- `#{items[0]}` reads a non-negative index from a `List` or array.
- String, boolean, numeric, and `null` literals are supported inside `#{...}`.
- Literal text can surround expressions, such as `deployment-#{id}`.

Property reads resolve a `getX()` getter, then an `isX()` getter, then a record component accessor
`x()`, and finally a public field. Record accessors are matched only for genuine record components,
so an ordinary public zero-argument method on a record is not exposed as a readable property.

Expressions cannot call methods or access class metadata. Syntax, statically known properties,
container types, and argument conversions are checked during compilation. Data-dependent failures
such as missing map keys, out-of-range indexes, null traversal, invalid IDs, and failed dynamic
value conversion become non-retryable Nexus `BAD_REQUEST` errors.

## Boundaries

- All mappings for one typed service must be visible to one annotation-processing compilation.
- Cross-JAR mapping aggregation is intentionally unsupported.
- Referenced workflow implementations must be registered on a worker polling the selected task
  queue.
- Activity and update operations are not implemented.
- This is a prototype API and may change before a stable release.
