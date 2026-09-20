pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "focus-engine"

// 순수 로직 코어를 composite build 로 참조한다. `co.byite.focus:focus-core` 의존성은
// 자동으로 ../../core/focus-core 의 :focus-core 프로젝트로 치환된다.
includeBuild("../../core/focus-core")

include(":engine", ":devapp")
