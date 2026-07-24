# Temporal Nexus Annotations

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
    implementation "io.github.quinnklassen:temporal-nexus-annotations:0.1.0"
    annotationProcessor "io.github.quinnklassen:temporal-nexus-annotations:0.1.0"
}
```

Kotlin DSL:

```kotlin
dependencies {
    implementation("io.github.quinnklassen:temporal-nexus-annotations:0.1.0")
    annotationProcessor("io.github.quinnklassen:temporal-nexus-annotations:0.1.0")
}
```

The library declares its compatible Temporal SDK version transitively. An application's direct
Temporal SDK dependency can select a newer compatible version.

## Maven

```xml
<dependency>
  <groupId>io.github.quinnklassen</groupId>
  <artifactId>temporal-nexus-annotations</artifactId>
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
            <groupId>io.github.quinnklassen</groupId>
            <artifactId>temporal-nexus-annotations</artifactId>
            <version>0.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Try the unpublished snapshot locally

Publish this checkout to Maven Local:

```bash
./gradlew clean build publishToMavenLocal
```

Then add `mavenLocal()` before `mavenCentral()` and use version `0.1.0-SNAPSHOT`:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation "io.github.quinnklassen:temporal-nexus-annotations:0.1.0-SNAPSHOT"
    annotationProcessor "io.github.quinnklassen:temporal-nexus-annotations:0.1.0-SNAPSHOT"
}
```

## Usage

Define an ordinary typed Nexus service:

```java
import io.nexusrpc.Operation;
import io.nexusrpc.Service;

@Service
public interface DeploymentService {
  @Operation
  String start(DeployInput input);

  @Operation
  void cancel(DeployInput input);
}
```

Map its operations onto normal Temporal implementation methods:

```java
import io.github.quinnklassen.temporal.nexusannotations.NexusSignalWorkflowOperation;
import io.github.quinnklassen.temporal.nexusannotations.NexusWorkflowOperation;

public final class DeploymentWorkflowImpl implements DeploymentWorkflow {
  @Override
  @NexusWorkflowOperation(
      service = DeploymentService.class,
      name = "start",
      workflowId = "deployment-#{id}",
      arguments = "#{name}")
  public String deploy(String name) {
    // Workflow implementation.
  }

  @Override
  @NexusSignalWorkflowOperation(
      service = DeploymentService.class,
      name = "cancel",
      workflowId = "deployment-#{id}",
      arguments = "#{reason}")
  public void cancel(String reason) {
    // Signal implementation.
  }
}
```

The processor generates one complete class named
`<TypedServiceSimpleName>NexusBindings` in the typed service's package. Register it explicitly:

```java
worker.registerWorkflowImplementationTypes(DeploymentWorkflowImpl.class);
worker.registerNexusServiceImplementation(DeploymentServiceNexusBindings.create());
```

Explicit registration means generated services are not exposed merely because their annotations
were compiled.

## Supported mappings

- `@NexusWorkflowOperation` starts a workflow asynchronously.
- `@NexusSignalWorkflowOperation` signals an existing workflow synchronously.
- `@NexusQueryWorkflowOperation` queries an existing workflow synchronously.
- `@NexusActivityOperation` deliberately fails compilation while this library targets Temporal Java
  SDK 1.37.0, which does not expose the Nexus activity bridge needed for asynchronous completion.
- `@NexusUpdateWorkflowOperation` deliberately fails compilation until asynchronous update support
  is implemented.

Workflow starts use the Nexus worker's task queue. Other execution settings retain Temporal SDK
defaults.

Every operation in a referenced Nexus service must have exactly one annotated mapping. Missing,
duplicate, incompatible, or malformed mappings fail compilation.

## Input expressions

The annotations use a deliberately small, input-only SpEL-like language:

- `#{input}` and `#{payload}` select the complete typed Nexus input.
- `#{property}` and `#{nested.property}` read JavaBean getters or public fields.
- `#{['key']}` reads a map key.
- String, boolean, numeric, and `null` literals are supported inside `#{...}`.
- Literal text can surround expressions, such as `deployment-#{id}`.

Expressions cannot call methods or access class metadata. Syntax is checked during compilation.
Missing properties, null traversal, invalid IDs, and failed value conversion become non-retryable
Nexus `BAD_REQUEST` errors.

## Boundaries

- All mappings for one typed service must be visible to one annotation-processing compilation.
- Cross-JAR mapping aggregation is intentionally unsupported.
- Referenced workflow implementations must be registered on the same worker as the generated Nexus
  service.
- Activity and update operations are not implemented.
- This is a prototype API and may change before a stable release.

## Development

```bash
./gradlew spotlessApply
./gradlew test
./gradlew build
```
