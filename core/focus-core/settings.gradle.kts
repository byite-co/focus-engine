// Gradle root for the pure-logic core. Run from this directory:
//   ./gradlew :focus-core:test
rootProject.name = "focus-engine-core"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":focus-core")
include(":gt-diff")
