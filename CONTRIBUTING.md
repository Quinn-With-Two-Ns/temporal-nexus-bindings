# Contributing

This project is a prototype, and its design and scope are still evolving. Contributions are
welcome for discussion, but proposed changes—including completed pull requests—may not be
accepted. Please open an issue before investing significant effort in a contribution.

Keep changes focused, add regression coverage for behavior changes, and preserve Java 8
compatibility for the published library.

## Development requirements

- JDK 21.
- The checked-in Gradle wrapper; no system Gradle installation is required.

The build runs on JDK 21 and emits Java 8-compatible bytecode.

## Quality checks

Format Java sources before running the complete verification suite:

```bash
./gradlew spotlessApply
./gradlew clean check javadoc
./gradlew build
```

The `check` lifecycle task enforces:

- Google Java Format through Spotless.
- JSpecify `@NullMarked` coverage.
- Error Prone and NullAway, with nullness errors and missing explicit null marking treated as build
  failures.
- Unit, annotation-processor, and Temporal integration tests.

Please do not suppress static-analysis findings unless the analyzer cannot express the valid
contract. Prefer making nullability and invariants explicit.

## Test an unpublished snapshot

Publish the current checkout to Maven Local:

```bash
./gradlew clean build publishToMavenLocal
```

In the consuming project, add `mavenLocal()` before `mavenCentral()` and use the snapshot version:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.quinn-with-two-ns:temporal-nexus-bindings:0.1.0-SNAPSHOT")
    annotationProcessor("io.github.quinn-with-two-ns:temporal-nexus-bindings:0.1.0-SNAPSHOT")
}
```

Use Maven Local only for development; published builds should resolve released artifacts from
Maven Central.
