plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.bugobliterator.itermbuddy"
    compileSdk = 37
    // Pinned to a build-tools release installed by scripts/cloud-setup.sh.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.bugobliterator.itermbuddy"
        minSdk = 34
        targetSdk = 37
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
    constraints {
        // activity-compose pulls lifecycle 2.9.x, whose bundled lint checks use APIs
        // the current lint no longer has (ObsoleteLintCustomCheck). 2.11.0 is current stable.
        implementation(libs.androidx.lifecycle.runtime)
    }

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
