import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "7.0.2"
    id("net.ltgt.errorprone") version "5.1.0"
}

description = "Compile-time Nexus bindings for Temporal workflow handlers"

val temporalVersion: String by project

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    api("io.temporal:temporal-sdk:$temporalVersion")
    api("org.jspecify:jspecify:1.0.0")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    errorprone("com.uber.nullaway:nullaway:0.13.8")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
    testImplementation("junit:junit:4.13.2")

    testAnnotationProcessor(files(sourceSets.main.get().output))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:-classfile", "-Xlint:-options"))
    options.release = 8
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        check("RequireExplicitNullMarking", CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:reference", "-quiet")
}

spotless {
    java {
        googleJavaFormat("1.24.0")
        target("src/*/java/**/*.java")
        targetExclude("**/generated/**")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name = "Temporal Nexus Bindings"
                description = project.description
                url = "https://github.com/Quinn-With-Two-Ns/temporal-nexus-bindings"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }

                developers {
                    developer {
                        id = "Quinn-With-Two-Ns"
                        name = "Quinn Klassen"
                    }
                }

                scm {
                    connection =
                        "scm:git:https://github.com/Quinn-With-Two-Ns/temporal-nexus-bindings.git"
                    developerConnection =
                        "scm:git:ssh://git@github.com/Quinn-With-Two-Ns/temporal-nexus-bindings.git"
                    url = "https://github.com/Quinn-With-Two-Ns/temporal-nexus-bindings"
                }
            }
        }
    }

    repositories {
        maven {
            name = "centralBundle"
            url = layout.buildDirectory.dir("central-bundle").get().asFile.toURI()
        }
    }
}

val signingKey = providers.environmentVariable("SIGNING_KEY")
val releaseVersion = providers.gradleProperty("version")
if (signingKey.isPresent) {
    signing {
        useInMemoryPgpKeys(
            signingKey.get(),
            providers.environmentVariable("SIGNING_PASSWORD").getOrElse(""),
        )
        sign(publishing.publications["mavenJava"])
    }
}

tasks.named("publishMavenJavaPublicationToCentralBundleRepository") {
    doFirst {
        check(releaseVersion.orNull?.isNotBlank() == true) {
            "Pass the release version explicitly with -Pversion=<version>."
        }
        check(!version.toString().endsWith("-SNAPSHOT")) {
            "Maven Central releases cannot use a -SNAPSHOT version."
        }
        check(signingKey.orNull?.isNotBlank() == true) {
            "SIGNING_KEY is required to create a Maven Central release bundle."
        }
    }
}
