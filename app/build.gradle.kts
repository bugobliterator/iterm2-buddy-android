plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.bugobliterator.itermbuddy"
    compileSdk = 36
    // Pinned to a build-tools release installed by scripts/cloud-setup.sh.
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "dev.bugobliterator.itermbuddy"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

// -PtestForks=1 runs unit tests serially, for timing comparisons.
val unitTestForks = providers.gradleProperty("testForks").map(String::toInt)
    .orElse(Runtime.getRuntime().availableProcessors())

tasks.withType<Test>().configureEach {
    maxParallelForks = unitTestForks.get()
    systemProperty("fixtures.dir", rootProject.file("fixtures").path)
    inputs.dir(rootProject.file("fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
}
