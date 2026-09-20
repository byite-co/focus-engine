plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 의 built-in Kotlin 은 buildscript classpath 에 있는 KGP 버전의 컴파일러를 쓴다. 적용하지 않고
    // classpath 에만 올려 컴파일러를 libs.versions.toml 의 kotlin 버전으로 고정한다(spike-r1 과 같은 방식).
    alias(libs.plugins.kotlin.android) apply false
}
