plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.timefreak7.fileforge.jobs"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":engine-image"))
    api(libs.work.runtime.ktx)
    implementation(libs.coroutines.android)
}
