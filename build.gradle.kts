plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// S11: every resolved dependency is pinned in a per-module gradle.lockfile.
// Refresh with: ./gradlew resolveAndLockAll --write-locks
subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { runCatching { it.resolve() } }
        }
    }
}
