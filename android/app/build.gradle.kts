plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.rafaeljjd.feedstudy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.rafaeljjd.feedstudy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-prototype"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The phone reaches the local server through `adb reverse tcp:8000 tcp:8000`.
        buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8000\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.webkit)
    implementation(libs.gson)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
