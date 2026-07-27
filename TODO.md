# Roadmap

This roadmap tracks the work needed to move Temporal Nexus Bindings from a prototype toward a
reliable, production-friendly library. Priorities reflect correctness and adoption value, not just
implementation cost.

## P0 — Release and compatibility foundations

- [x] Add continuous integration.
  - Run `spotlessCheck`, `check`, `javadoc`, and the full build on supported JDKs.
- [ ] Add a Temporal SDK compatibility matrix.
  - Test the minimum supported SDK version.
  - Test the newest supported SDK version.
  - Document which features require newer SDK or server capabilities.
- [ ] Replace prototype-facing diagnostics such as `TODO for the real implementation` with
      actionable error messages.

## P1 — Correct targeting and execution control

### Target a specific workflow execution

- [ ] Add optional `runId` and `firstExecutionRunId` expressions to signal and query mappings.
- [ ] Add the same targeting options to update mappings when update support is implemented.
- [ ] Generate `WorkflowTargetOptions` when execution-specific targeting is configured.
- [ ] Validate target expressions at compile time and reject empty evaluated values as Nexus
      `BAD_REQUEST` errors.
- [ ] Add integration coverage for workflow ID reuse and continue-as-new chains.

### Configure workflow execution

- [x] Support the most commonly needed `WorkflowOptions`:
  - Workflow execution, run, and task timeouts.
  - Workflow ID reuse and conflict policies.
  - Retry policy.
  - Start delay.
  - Priority.
  - Static summary and details.
- [ ] Design an options-provider extension point for complex settings such as memo, typed search
      attributes, and versioning overrides.
- [x] Keep common scalar settings convenient without turning `@WorkflowOperation` into an
      unbounded mirror of `WorkflowOptions`.
- [x] Validate literal option values during annotation processing.
- [x] Add generated-handler tests proving configured values reach the Temporal client.

### Customize workflow cancellation

- [ ] Define a `WorkflowCancellationHandler` extension point for workflow-backed operations.
- [ ] Provide built-in handlers for:
  - Leaving the workflow running after acknowledging the cancellation request.
  - Cancelling the workflow.
  - Signalling the workflow, with optional cancellation-context-derived arguments.
  - Terminating the workflow, with a configurable reason and optional details.
- [ ] Let generated bindings configure a cancellation-handler instance per workflow operation while
      preserving workflow cancellation as the default for the no-argument `create()` path.
- [ ] Keep caller-side `NexusOperationCancellationType.ABANDON` distinct from a handler-side no-op:
      the former sends no cancellation request, while the latter acknowledges a request without
      changing the underlying workflow.
- [ ] Require built-in and custom handlers to be thread-safe, return promptly, and document
      idempotency expectations for cancellation request retries.
- [ ] Add handler-factory, generated-binding, and end-to-end coverage for every built-in behavior
      and custom-handler delegation.

### Control operation failure handling

- [ ] Define an `OperationFailureHandler<R>` extension point that can propagate a Temporal
      invocation failure, translate it to a Nexus failure, or recover with a successful result.
- [ ] Add a `failureHandler` class-literal parameter to each operation-mapping annotation, with the
      standard Temporal-to-Nexus behavior as its default.
- [ ] Provide built-in handler implementations for:
  - Standard Temporal-to-Nexus failure handling.
  - Ignoring `WorkflowNotFoundException` for `Void` operations.
  - Translating `WorkflowNotFoundException` to a Nexus `NOT_FOUND` failure.
- [ ] Allow custom handlers with an accessible no-argument constructor and validate during
      annotation processing that their result type is compatible with the Nexus operation output.
- [ ] Reject the ignore-not-found handler on non-`Void` operations; require those operations to use
      a custom handler that supplies an explicit fallback result.
- [ ] Invoke failure handlers only for failures from the outbound Temporal operation so input
      expression and option-validation `BAD_REQUEST` failures cannot be swallowed.
- [ ] Instantiate each configured handler once, document the thread-safety requirement, and add
      processor, runtime, and end-to-end coverage for default, built-in, and custom behavior.

### Implement workflow updates

- [ ] Implement synchronous `@UpdateOperation` using `WorkflowStub.update`.
- [ ] Support update name, workflow targeting, argument expressions, and generic result types.
- [ ] Document the latency and handler-timeout limitations of synchronous updates.
- [ ] Convert invalid input expressions into non-retryable Nexus `BAD_REQUEST` errors.
- [ ] Add processor, runtime, and end-to-end integration coverage.
- [ ] Add asynchronous updates only when the SDK exposes an appropriate Nexus completion and
      cancellation mechanism.

### Add atomic start-and-command operations

- [ ] Design `@SignalWithStartOperation`.
- [ ] Design `@UpdateWithStartOperation`.
- [ ] Reuse the workflow start and command argument-mapping rules rather than introducing a second
      expression system.
- [ ] Define workflow conflict-policy behavior explicitly.
- [ ] Add race-condition and already-running workflow integration tests.

## P1 — Generated API ergonomics

- [x] Change generated `create()` methods to return the generated binding type instead of `Object`.
- [x] Add a generated registration helper while preserving explicit service exposure.
- [x] Mark generated sources with a source-level-appropriate `@Generated` annotation.
- [x] Include the originating service and operation in generated-code documentation.

### Compose generated and handwritten operation handlers

- [ ] Define a processor-only `@NexusServiceFragment(service = ...)` annotation for classes that
      contribute handwritten handlers to an otherwise generated Nexus service.
- [ ] Reuse Nexus SDK `@OperationImpl` methods inside fragments instead of introducing another
      operation-handler method contract.
- [ ] Generate one complete `@ServiceImpl` wrapper that delegates each operation to exactly one
      annotation-generated handler or fragment method; fragments must not be directly registerable
      as partial Nexus service implementations.
- [ ] Generate typed `create(...)` and `register(...)` methods that require all fragment instances,
      allowing callers to construct fragments with application dependencies.
- [ ] Validate fragment methods during annotation processing:
  - The method is public, non-static, non-generic, parameterless, and has no `throws` clause.
  - Its name identifies an operation in the selected typed Nexus service.
  - Its `OperationHandler` input and output types match the service operation.
  - Every service operation has exactly one generated or handwritten provider.
- [ ] Report missing and duplicate providers with both contributing source locations.
- [ ] Call each fragment handler factory once while constructing the generated service binding.
- [ ] Initially require fragments and annotation mappings for one service to be visible in the same
      annotation-processing compilation, consistent with the existing aggregation boundary.
- [ ] Add processor and end-to-end coverage for generated-only, fragment-only, hybrid,
      dependency-injected, missing, duplicate, and type-incompatible compositions.

- [ ] Add a complete example application showing:
  - Worker and Nexus endpoint setup.
  - Explicit generated-service registration.
  - Workflow, signal, query, and update calls.
  - Split mappings across multiple implementation classes.
  - Header and request metadata expressions.

## P2 — Expression and request metadata improvements

- [ ] Add a bounded default/coalescing expression, for example:

  ```text
  #{nexus.headers['x-region'] ?: 'us-west'}
  ```

- [ ] Expose additional safe Nexus request metadata:
  - Service and operation names.
  - Request deadline.
  - Callback URL and callback headers where appropriate.
  - Request links where they can be represented safely.
- [ ] Decide how null-safe traversal should behave and preserve compile-time type validation.
- [ ] Cache resolved property accessors or generate typed extractors to reduce per-call reflection.
- [ ] Keep method invocation, class metadata access, and general-purpose SpEL out of scope.

## P2 — Kotlin support

Kotlin sources are reachable only through kapt, and the README now documents the required setup,
payload conversion, and known limitations. The remaining work is bringing the experience up to the
Java one.

- [ ] Add a kapt fixture to CI.
  - No Kotlin path is exercised by any test today, so every documented Kotlin behavior is reasoned
    about rather than verified.
  - Cover a Kotlin typed service, a Kotlin workflow implementation, and a mixed Kotlin/Java mapping.
  - Confirm generated Java bindings resolve from Kotlin call sites in the same module.
  - Cover expression traversal over `data class` inputs, including the `is`-prefixed accessor rule.
- [ ] Report actionable diagnostics for Kotlin constructs that cannot be mapped.
  - `suspend` handler methods, whose hidden `Continuation` parameter is currently reported as an
    argument-count mismatch.
  - Default arguments and `@JvmOverloads` on mapped methods, which copy the annotation onto
    synthetic overloads and surface as a duplicate mapping.
- [ ] Decide how to handle Kotlin declaration-site variance.
  - A Kotlin `List<String>` parameter compiles to `List<? extends String>`, so a Kotlin service
    interface paired with a Java handler can fail the strict return-type and direct-input checks on
    types that are identical in source.
  - Either keep `@JvmSuppressWildcards` as the documented workaround or compare erasure plus
    assignability instead of requiring an exact type match.

## P2 — Better model-type support

- [ ] Support expression traversal through Java record components.
  - Spring SpEL reads a property by trying `getX()`, `isX()`, and then a record-style plain
    zero-argument accessor such as `x()`.
  - The current bounded expression implementation only supports `getX()`, `isX()`, and public
    fields at compile time and runtime.
  - Detect genuine record components explicitly so record support does not accidentally expose
    every public zero-argument method.
- [ ] Finish Kotlin accessor support.
  - Ordinary `val` and `var` properties already resolve through the generated `getX()` accessors,
    so `data class` inputs work; the gaps are Kotlin's accessor-naming rules.
  - Resolve `is`-prefixed properties by their declared name. Kotlin compiles `val isActive` to
    `isActive()`, so the property is currently reachable only as `#{active}`.
  - Decide whether to support `@JvmInline value class` properties, whose accessors carry mangled
    JVM names.
  - Reject `internal` properties, whose accessors carry a module-name suffix, with a message that
    names the cause instead of reporting that the property does not exist.
- [ ] Support Protobuf-generated message accessors.
- [ ] Recognize boolean `isX` and fluent zero-argument accessors such as `customerId()`.
  - Treat general fluent accessors as a separate design decision from record components because
    unrestricted method matching would broaden the expression language's security surface.
- [ ] Add processor and runtime coverage for each supported model style.

## P2 — Nexus service method overloads

- [ ] Support overloaded Java service methods when their Nexus operation names are unique.
- [ ] Decouple generated handler identity from the Java source method name.
- [ ] Determine whether this requires a generated bridge service or an upstream programmatic
      registration API.
- [ ] Continue rejecting duplicate Nexus operation names.
- [ ] Add compile-time and registration tests for overloaded methods with distinct input/output
      types.

## P2 — Incremental and multi-round processing

- [ ] Declare the annotation processor as Gradle `aggregating`.
- [ ] Collect mappings across all relevant annotation-processing rounds before generating services.
- [ ] Keep generated output deterministic regardless of source discovery order.
- [ ] Add tests where another processor generates a mapped type in a later round.
- [ ] Verify incremental rebuilds after adding, changing, and removing a mapping.

## P3 — KSP support

kapt is in maintenance mode, so Kotlin-only projects will increasingly enable KSP alone. KSP does
not run `javax.annotation.processing` processors and no adapter exists, so support means a second
front end rather than a shim. Treat the artifact split as a prerequisite that is worth doing on its
own schedule, and the second front end as a decision gated on demand.

- [ ] Split the published artifact before adding a second front end.
  - `temporal-nexus-bindings` keeps the annotations, runtime handlers, and shared expression parser.
  - `temporal-nexus-bindings-apt` carries the javac processor that ships today, so only the
    processor coordinate changes for existing consumers.
  - Promote `ExpressionModel` from package-private to a shared internal API so both front ends parse
    expressions identically instead of duplicating the grammar.
  - Rework the publishing workflow, which hardcodes a single artifact name in its digest capture and
    bundle validation.
- [ ] Decide whether a KSP front end is worth its duplication cost.
  - `ExpressionTypeValidator` and `NexusAnnotatedHandlerProcessor` are bound to `javax.lang.model`
    throughout and have no reuse path; the validation rules would exist twice and must not drift.
  - A separate artifact keeps `kotlin-stdlib` and `symbol-processing-api` off every Java consumer's
    processor path and avoids adding a Kotlin version axis to the Temporal compatibility matrix.
  - Until this ships, kapt keeps Kotlin unblocked, so weigh it against the P1 correctness work.
- [ ] If implemented, keep the two front ends interchangeable.
  - Reuse the shared expression parser and mirror the generated `create()` and `register(...)`
    shapes exactly so consumers can switch without source changes.
  - Run the same mapping-validation cases against both front ends.
- [ ] If implemented, take the Kotlin fidelity KSP makes available.
  - `KSFunctionDeclaration.findOverridee()` replaces the manual walk up the Temporal handler
    interfaces.
  - Kotlin property, value-class, and `internal` naming are visible directly rather than through
    kapt's Java projection, which removes several documented limitations.

## P3 — Cross-module service composition

- [ ] Decide whether cross-JAR mapping aggregation is a supported long-term goal.
- [ ] If supported, define a generated metadata/index format for partial service contributions.
- [ ] Compose contributions deterministically and reject duplicates with actionable source
      information.
- [ ] Preserve the rule that a registered service must have a complete operation set.
- [ ] Test Gradle and Maven multi-module consumers.
- [ ] If aggregation remains out of scope, document the single-compilation boundary as a permanent
      compatibility constraint.

## P3 — Standalone Activity operations

- [ ] Re-evaluate `@ActivityOperation` against the supported Temporal Java SDK versions.
- [ ] Confirm that the SDK and server provide all required pieces:
  - Standalone Activity start and result completion.
  - A Nexus-compatible asynchronous operation token.
  - Cancellation propagation.
  - Retry and timeout semantics.
- [ ] Do not implement a custom token protocol using Temporal internal APIs.
- [ ] Once supported, implement activity ID, task queue, timeout, retry, priority, and search
      attribute options.
- [ ] Add completion, cancellation, retry, timeout, and duplicate-ID integration tests.

## Ongoing quality work

- [ ] Add runtime integration tests for documented `BAD_REQUEST` behavior:
  - Missing headers and map keys.
  - Null traversal and out-of-range indexes.
  - Empty workflow IDs and task queues.
  - Failed direct-input coercion.
- [ ] Cover the default Nexus worker task-queue fallback end to end.
- [ ] Cover zero-argument service and Temporal handler mappings.
- [ ] Test generic and container input/output types through generated handlers.
- [ ] Keep the README, Javadocs, compatibility matrix, and examples synchronized with each shipped
      feature.
