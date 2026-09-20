// :engine — Android 측정 엔진(기기 층). 순수 로직은 composite build 로 붙는 core/focus-core 에 있다.
// 프레임·랜드마크 타입은 co.byite.focus.engine.pipeline.* 안에서만 쓴다(DataBoundaryTest 가 검사).
plugins {
    alias(libs.plugins.android.library)
}

// algorithm_version = git commit. CI 는 GITHUB_SHA, 로컬은 git rev-parse. 둘 다 없으면 "unknown".
val gitSha: String = System.getenv("GITHUB_SHA")?.take(12)?.takeIf { it.isNotBlank() }
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
    ?: "unknown"

android {
    namespace = "co.byite.focus.engine"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 어떤 경로로 들어오든 datatransport 는 빌드에서 뺀다(no-op 스텁이 src/main/java 에 있다).
configurations.all {
    exclude(group = "com.google.android.datatransport")
}

dependencies {
    // composite build: ../../core/focus-core 의 :focus-core 로 치환된다.
    api("co.byite.focus:focus-core:0.2.0-v0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    // tasks-core 가 끌고 오는 com.google.android.datatransport(transport-backend-cct) 는 매니페스트에
    // INTERNET 권한을 선언한다. 오픈소스 빌드의 TasksStatsLoggerFactory 는 DummyLogger 만 쓰므로
    // 통째로 제외한다. 매니페스트에서도 tools:node="remove" 로 한 번 더 막고 CI 가 merged manifest 를 검사한다.
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.android.datatransport")
    }

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
}
