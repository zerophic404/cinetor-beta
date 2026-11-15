import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mythbrix.cinetor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mythbrix.cinetor"
        minSdk = 24
        targetSdk = 35
        // CI run number → monotonically increasing versionCode (updates install over older builds).
        val run = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull() ?: 1
        versionCode = run
        versionName = (System.getenv("CINETOR_VERSION") ?: "").trim().removePrefix("v").ifEmpty { "0.1.$run" }

        // TMDB v4 read token from CI secret (never committed). Empty → mock catalog only.
        val tmdbToken = (System.getenv("TMDB_TOKEN") ?: "").trim().replace("\"", "")
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
        // CI run number shown in About (Task 5.3); "local" outside Actions.
        val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "local").trim()
        buildConfigField("String", "BUILD_NUMBER", "\"$buildNumber\"")
    }

    // Release keystore (PKCS12) from CI secrets; same key for debug so builds update in place.
    val ksPath = (System.getenv("CINETOR_KEYSTORE_PATH") ?: "").trim()
    val hasKeystore = ksPath.isNotEmpty() && file(ksPath).exists()
    signingConfigs {
        if (hasKeystore) {
            create("cinetor") {
                storeFile = file(ksPath)
                storePassword = System.getenv("CINETOR_KEYSTORE_PASS")
                keyAlias = System.getenv("CINETOR_KEY_ALIAS") ?: "cinetor"
                keyPassword = System.getenv("CINETOR_KEY_PASS")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("cinetor")
        }
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("cinetor")
            // R8 full mode (gradle.properties) + resource shrinking (Task 7.1).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Per-ABI APKs + one universal APK (phone + TV boxes). Native code = Compose graphics-path only.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Compose: strong skipping is on by default (Kotlin 2.0.20+); immutable catalog models declared stable.
// Compiler stability reports/metrics → build/compose-reports (uploaded by CI for review).
composeCompiler {
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose-stability.conf")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-reports")
}

/**
 * Copies the shared mock catalog (json, images, subs) into a generated assets dir so it is
 * never duplicated in git. Accessible at runtime as `mock/home.json`, `mock/images/...`.
 */
abstract class CopyMockAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mockDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(mockDir) {
                include("*.json", "images/**", "subs/**")
                exclude("catalog.schema.json")
            }
            into(outputDir.dir("mock"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        val taskName = "copy" + variant.name.replaceFirstChar { it.uppercase() } + "MockAssets"
        val copyTask = tasks.register<CopyMockAssetsTask>(taskName) {
            mockDir.set(rootProject.file("../shared/mock"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, CopyMockAssetsTask::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Player (Phase 3): ONE shared ExoPlayer; HLS/DASH/SS/RTSP + PlayerView surface only (custom Compose controls).
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // Downloads (Task 3.7): Room (chunk offsets), WorkManager (constraints/resume).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Baseline profile (src/main/baseline-prof.txt) installed on first launch / by Play (Task 7.1).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
