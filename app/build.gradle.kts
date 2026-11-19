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

/** Copies the fixtures the device tests read into a generated androidTest assets directory, flat. */
abstract class CopyDeviceTestFixtures : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        fixtures.files.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val deviceTestFixtures = tasks.register<CopyDeviceTestFixtures>("deviceTestFixtureAssets") {
    fixtures.from(
        // HevcDecodePathDeviceTest reads terminal-clip.json (the clip as the Mac sends it).
        rootProject.layout.projectDirectory.file("fixtures/media/hevc/terminal-clip.json"),
        // MentionParserDeviceTest runs the parser vectors on the device's ICU regex engine.
        rootProject.layout.projectDirectory.file("fixtures/chat/mention-parser.json"),
    )
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(deviceTestFixtures, CopyDeviceTestFixtures::outputDir)
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
    // The activity ComposeTestRule hosts the instrumented gesture test in.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// -PtestForks=1 runs unit tests serially, for timing comparisons.
val unitTestForks = providers.gradleProperty("testForks").map(String::toInt)
    .orElse(Runtime.getRuntime().availableProcessors())

val chatMemoryTestName = "chatMemoryTest"
// Heap-bound tests only: ui.live.LiveInputMemoryTest is about input state, not heap, and stays in testDebugUnitTest.
val memoryTests = "dev.bugobliterator.itermbuddy.core.*MemoryTest"

val realIterm2TestName = "realIterm2Test"
// Against a real iTerm2 on a macOS runner (.github/workflows/real-iterm2.yml); only realIterm2Test runs them.
val realIterm2Tests = "dev.bugobliterator.itermbuddy.realiterm2.*"
// What the workflow hands those tests (ci/real-iterm2/README.md).
val realIterm2Environment = listOf(
    "REAL_ITERM2",
    "REAL_ITERM2_PAIRING_FILE",
    "REAL_ITERM2_RELAY_URL",
    "REAL_ITERM2_CONNECT_TIMEOUT_SECONDS",
    "REAL_ITERM2_REQUEST_TIMEOUT_SECONDS",
    "REAL_ITERM2_CHAT_TIMEOUT_SECONDS",
    "REAL_ITERM2_FAKE_MODEL_URL",
)

tasks.withType<Test>().configureEach {
    // The real iTerm2 tests share one pairing, and the Mac serves one phone: one JVM, one test at a time.
    maxParallelForks = if (name == realIterm2TestName) 1 else unitTestForks.get()
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
    // Memory tests assert a peak heap, so they run only in chatMemoryTest's bounded JVM.
    if (name != chatMemoryTestName) filter { excludeTestsMatching(memoryTests) }
    if (name != realIterm2TestName) filter { excludeTestsMatching(realIterm2Tests) }
}

/**
 * Runs the debug unit tests named `core.*MemoryTest` in JVMs with a 128 MiB heap, where a decode whose
 * peak does not fit fails with an OutOfMemoryError. `check` runs it; `testDebugUnitTest` does not.
 */
val chatMemoryTest = tasks.register<Test>(chatMemoryTestName) {
    description = "Runs the chat memory tests in a bounded heap."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val unitTests = tasks.getByName<Test>("testDebugUnitTest")
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter { includeTestsMatching(memoryTests) }
    maxHeapSize = "128m"
    // One collector whose peak is predictable in a small heap.
    jvmArgs("-XX:+UseSerialGC")
}

tasks.named("check") { dependsOn(chatMemoryTest) }

/**
 * Runs `realiterm2.*` against a real iTerm2 that `ci/real-iterm2/` set up: only with
 * `REAL_ITERM2=1`, and only from `.github/workflows/real-iterm2.yml` (the tests skip otherwise).
 * Neither `check` nor `testDebugUnitTest` runs it. Never up to date: it tests a live Mac.
 */
tasks.register<Test>(realIterm2TestName) {
    description = "Runs the real iTerm2 integration tests (needs REAL_ITERM2=1 and ci/real-iterm2)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val unitTests = tasks.getByName<Test>("testDebugUnitTest")
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter { includeTestsMatching(realIterm2Tests) }
    realIterm2Environment.forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
