plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "kr.co.byite.focus.spike"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "kr.co.byite.focus.spike"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-spike-r1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":spikecore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
}
