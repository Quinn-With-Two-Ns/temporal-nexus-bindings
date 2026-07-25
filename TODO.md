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
