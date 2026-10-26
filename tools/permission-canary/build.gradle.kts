plugins {
    alias(libs.plugins.android.application)
}

// Not shipped. CI builds this and expects scripts/check_manifest.py to reject it (S1 self-test).
android {
    namespace = "io.github.timefreak7.fileforge.canary"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.timefreak7.fileforge.canary"
        minSdk = 36
        targetSdk = 36
    }
}
