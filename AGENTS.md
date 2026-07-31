# AGENTS.md

Guidance for AI coding agents working in this repository. Human-facing docs live in
`README.md` (usage), `CONTRIBUTING.md` (workflow), and `TODO.md` (roadmap).

## What this project is

`temporal-nexus-bindings` is a Java **annotation processor** plus a small runtime support library.
It reads Temporal workflow/activity implementation classes annotated with `@WorkflowOperation`,
`@SignalOperation`, `@QueryOperation`, `@UpdateOperation`, and `@ActivityOperation`, and generates a
complete Nexus service implementation named `<TypedServiceSimpleName>NexusBindings` in the typed
Nexus service's package. Operations that need handwritten logic are contributed by
`@NexusServiceFragment` classes holding ordinary Nexus SDK `@OperationImpl` methods, which the
same generated class delegates to.

It is an experimental, community-maintained project, not an official Temporal SDK module.

## Repository layout

```
src/main/java/io/github/quinn_with_two_ns/temporal/nexus/
  ServiceMapping.java, NexusServiceFragment.java, WorkflowOperation.java, SignalOperation.java,
  QueryOperation.java, UpdateOperation.java, ActivityOperation.java, WorkflowStartOptions.java
      Public annotation API. Every public type is @Experimental.
  internal/
    NexusAnnotatedHandlerProcessor.java   The javac processor: discovers mappings, validates them,
                                          and emits generated Java source as strings.
    GeneratedNexusOperationHandlers.java  Runtime entry points the generated code calls.
    InputExpression.java                  Runtime evaluation of the `#{...}` expression language.
    ExpressionModel.java                  Shared parse tree for runtime and compile-time use.
    ExpressionTypeValidator.java          Compile-time type checking of expressions.
    WorkflowStartOptionParsers.java       Literal/dynamic parsing of workflow start options.
src/main/resources/META-INF/services/javax.annotation.processing.Processor
      Registers the processor. Update it if the processor class is ever renamed or moved.
src/test/java/.../ProcessorValidationTest.java          Compile-time behavior (success + failures).
src/test/java/.../GeneratedBindingsIntegrationTest.java End-to-end against TestWorkflowEnvironment.
src/test/java/.../internal/InputExpressionTest.java     Expression runtime unit tests.
```

`internal` is not public API. Classes there may change without notice, but note that
`GeneratedNexusOperationHandlers` and `WorkflowStartOptionParsers` are `public` **because generated
user code calls them** — changing their signatures is a breaking change for anyone with previously
generated sources on their classpath.

## Build and verification

JDK 21 is required to build; the published bytecode targets Java 8. Use the checked-in wrapper.

```bash
./gradlew spotlessApply                 # format first — CI runs spotlessCheck
./gradlew clean check javadoc           # full verification suite
./gradlew build
```

Before proposing a change as complete, run `./gradlew --no-daemon clean spotlessCheck check javadoc`
— that is exactly what `.github/workflows/ci.yml` runs.

`check` enforces:

- Google Java Format 1.24.0 via Spotless.
- Error Prone with NullAway at `ERROR`, plus `RequireExplicitNullMarking` at `ERROR`.
- JSpecify `@NullMarked` coverage — every package needs a `package-info.java` with
  `@org.jspecify.annotations.NullMarked`. Adding a new package without one fails the build.
- The three test classes above.

Do not suppress static-analysis findings. Make nullability explicit instead.

Useful narrower commands while iterating:

```bash
./gradlew test --tests '*ProcessorValidationTest*'
./gradlew test --tests '*InputExpressionTest*'
./gradlew publishToMavenLocal            # for testing against a consuming project
```

## Constraints that are easy to violate

- **Java 8 source and target.** `options.release = 8` applies to main *and* test compilation. No
  `var`, no records, no `List.of`, no text blocks, no `Map.entry`, no switch expressions. String
  concatenation over text blocks in tests is intentional, not stylistic drift.
- **Generated code must also compile on Java 8** and on modern JDKs. `ProcessorValidationTest`
  compiles the same fixture with `--release 8` and `--release 17` because the `@Generated`
  annotation differs between them (`javax.annotation.Generated` vs
  `javax.annotation.processing.Generated`). Any change to emitted source needs to hold for both.
- **The processor emits source as plain strings.** There is no JavaPoet dependency. Keep the emitted
  formatting consistent with what the existing tests assert on, and fully qualify types in generated
  code rather than adding imports.
- **Deterministic output.** Iteration over discovered mappings is sorted; archives are configured
  for reproducible builds. Do not introduce `HashMap`/`HashSet` iteration into anything that reaches
  generated output ordering.
- **Temporal SDK version** is pinned by `temporalVersion` in `gradle.properties` (currently 1.37.0)
  and is an `api` dependency. Activity and update operations deliberately fail compilation because
  the pinned SDK lacks the needed bridge; do not "fix" those by silently generating handlers.
- **Every public type carries `io.temporal.common.Experimental`** and full Javadoc — `javadoc` runs
  in CI.

## Where the interesting logic lives

- Adding or changing an **annotation parameter**: the annotation type in the public package, its
  extraction in `NexusAnnotatedHandlerProcessor`, literal validation (often
  `WorkflowStartOptionParsers`), the emitted call site, and the runtime consumption in
  `GeneratedNexusOperationHandlers`. Also update the parameter tables in `README.md`.
- Changing the **expression language**: `ExpressionModel` (parsing, shared),
  `ExpressionTypeValidator` (compile-time), `InputExpression` (runtime). Parse changes must land in
  the shared model so compile-time and runtime never diverge.
- **Error taxonomy:** anything statically knowable is a compilation error with a precise message;
  anything data-dependent (missing map key, out-of-range index, null traversal, bad ID, failed
  conversion) becomes a non-retryable Nexus `BAD_REQUEST` at runtime. Keep new failures on the
  correct side of that line.

## Testing conventions

- `ProcessorValidationTest` builds a single `test.TestSource` string, runs the processor with
  `-proc:only` through `ToolProvider.getSystemJavaCompiler()`, and asserts on success plus generated
  source content, or on failure plus an exact diagnostic substring. **Every new compile-time
  rejection needs a test asserting its message text.**
- `GeneratedBindingsIntegrationTest` runs against `TestWorkflowEnvironment` with real workers and a
  Nexus endpoint. Its bindings are generated during test compilation via
  `testAnnotationProcessor(files(sourceSets.main.get().output))` in `build.gradle.kts` — so the
  test sources reference `DeploymentServiceNexusBindings`, a class that does not exist in the tree.
  That is expected; do not create it by hand.
- Behavior changes need regression coverage. Prefer asserting on observable generated output or
  end-to-end behavior over internal structure.

## Git and PR conventions

- Commit messages in this repo are short, imperative, and unscoped ("Add workflow options",
  "Mark source as generated"). Match that.
- Do not commit `build/`, `.gradle/`, or IDE files (see `.gitignore`).
- Do not bump the version in `gradle.properties` or hand-edit release workflows; publishing is a
  manual `workflow_dispatch` on `.github/workflows/publish.yml` with an explicit `-Pversion`.
- Keep `README.md` and `TODO.md` in sync when behavior changes — the README documents every
  annotation parameter, and the roadmap checkboxes track what has landed.
- Open an issue before large design changes; per `CONTRIBUTING.md`, completed pull requests may
  still be declined.
