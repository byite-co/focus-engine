// :devapp — 개발용 앱. 시작/정지, 구간 마커, 상태, 요약. 엔진은 :engine 에 있다.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "co.byite.focus.devapp"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "co.byite.focus.devapp"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-v0ab"
        // 실측 기기(arm64)만 대상으로 해 APK 를 줄인다. tasks-core 네이티브 라이브러리가 ABI 마다 크다.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        // 모델 파일(.task)은 압축하지 않고 APK 에 넣는다.
        noCompress.add("task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// :engine 과 같은 이유로 datatransport 를 뺀다(INTERNET 권한을 선언하는 라이브러리).
configurations.all {
    exclude(group = "com.google.android.datatransport")
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
}
