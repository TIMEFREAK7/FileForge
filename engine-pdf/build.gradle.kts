plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.timefreak7.fileforge.pdf"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
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
    // Bouncy Castle is only for certificate-encrypted PDFs, which FileForge does not open; it also carries
    // high and critical advisories (S11). Password PDFs use the platform's own AES and RC4.
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
