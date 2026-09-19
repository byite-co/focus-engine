// :gt-diff — JVM-only CLI wrapper around :focus-core (file IO lives here, not in the core).
plugins {
    kotlin("jvm")
    application
}

group = "co.byite.focus"
version = "0.2.0-v0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":focus-core"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("co.byite.focus.tools.gtdiff.GtDiffMain")
    applicationName = "gt-diff"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
