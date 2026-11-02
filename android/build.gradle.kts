plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// ~/Documents is often synced by iCloud, whose conflict copies ("Foo 2.class") break the build.
val buildRoot = File(System.getProperty("user.home"), ".cache/feedstudy-build")
allprojects {
    layout.buildDirectory.set(File(buildRoot, project.name))
}
