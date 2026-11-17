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
        // CI passes -PciVersionCode / -PciVersionName; Firebase App Distribution shows them.
        versionCode = providers.gradleProperty("ciVersionCode").orNull?.let {
            it.toIntOrNull() ?: throw GradleException("ciVersionCode must be an integer, got '$it'")
        } ?: 1
        versionName = providers.gradleProperty("ciVersionName").orNull ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

}

kotlin {
    jvmToolchain(17)
}

/** Copies the one fixture the device test reads into a generated androidTest assets directory. */
abstract class CopyHevcClipAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val clip: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        clip.get().asFile.copyTo(out.resolve(clip.get().asFile.name))
    }
}

val hevcClipAssets = tasks.register<CopyHevcClipAssets>("hevcClipAndroidTestAssets") {
    // HevcDecodePathDeviceTest reads terminal-clip.json (the clip as the Mac sends it) from the test APK.
    clip.set(rootProject.layout.projectDirectory.file("fixtures/media/hevc/terminal-clip.json"))
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(hevcClipAssets, CopyHevcClipAssets::outputDir)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // MlKitException comes with it (com.google.mlkit:common, as the scanner declares).
    implementation(libs.play.services.code.scanner)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.noise.java)
    // JSON tree API (JsonObject) for the wire envelope; no serialization compiler plugin.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    constraints {
        // activity-compose pulls lifecycle 2.9.x, whose bundled lint checks use APIs
        // the current lint no longer has (ObsoleteLintCustomCheck). 2.11.0 is current stable.
        implementation(libs.androidx.lifecycle.runtime)
    }

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests (AndroidKeyStore); run on a device only.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// -PtestForks=1 runs unit tests serially, for timing comparisons.
val unitTestForks = providers.gradleProperty("testForks").map(String::toInt)
    .orElse(Runtime.getRuntime().availableProcessors())

tasks.withType<Test>().configureEach {
    maxParallelForks = unitTestForks.get()
    systemProperty("fixtures.dir", rootProject.file("fixtures").path)
    // RelayEndToEndTest runs only when scripts/relay-it.sh points RELAY_DIR at a relay checkout.
    providers.environmentVariable("RELAY_DIR").orNull?.let { relayDir ->
        environment("RELAY_DIR", relayDir)
        inputs.property("relayDir", relayDir)
    }
    // Only the committed fixtures; fixtures/harness holds the Swift generator and its build output.
    inputs.files(rootProject.fileTree("fixtures") { exclude("harness/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("fixtures")
}
