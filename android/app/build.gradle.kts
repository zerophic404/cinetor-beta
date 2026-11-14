import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mythbrix.cinetor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mythbrix.cinetor"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // TMDB v4 read token from CI secret (never committed). Empty → mock catalog only.
        val tmdbToken = (System.getenv("TMDB_TOKEN") ?: "").trim().replace("\"", "")
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
