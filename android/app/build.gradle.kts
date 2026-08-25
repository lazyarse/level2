import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Git-driven versioning: the latest tag drives versionName, the commit count
// drives versionCode — releases are cut by pushing a vX.Y.Z tag, never by
// hand-editing this file. Builds outside a git repo fall back to placeholders.
fun runGit(vararg args: String): String? = try {
    val p = ProcessBuilder("git", *args).redirectErrorStream(true).start()
    p.waitFor(5, TimeUnit.SECONDS)
    p.inputStream.bufferedReader().readText().trim()
        .takeIf { p.exitValue() == 0 && it.isNotEmpty() }
} catch (_: Exception) {
    null
}

val gitDescribe: String? = runGit("describe", "--tags", "--dirty")

android {
    namespace = "io.securitycam.level2"
    // 37 = highest installed platform (flutter_secure_storage compiled against 37).
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (Duration, Instant, …) is API 26+; desugar it for minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "io.securitycam.level2"
        // 28 (not a hard product floor): MediaPipe's tasks-vision JNI needs
        // aligned_alloc (bionic API 28) and strtod_l/newlocale (API 26); every
        // x86_64-capable release (0.10.26+) carries both requirements.
        minSdk = 28
        targetSdk = 35
        versionCode = runGit("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = gitDescribe?.removePrefix("v") ?: "0.0.0-untagged"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumentation runs against the minified staging build by default so
    // R8 regressions surface in every emulator pass, not only in release QA.
    testBuildType = "staging"

    signingConfigs {
        val storeFile = (project.findProperty("LEVEL2_RELEASE_STORE_FILE") as String?)
            ?: System.getenv("LEVEL2_RELEASE_STORE_FILE")
        val storePassword = (project.findProperty("LEVEL2_RELEASE_STORE_PASSWORD") as String?)
            ?: System.getenv("LEVEL2_RELEASE_STORE_PASSWORD")
        val keyAlias = (project.findProperty("LEVEL2_RELEASE_KEY_ALIAS") as String?)
            ?: System.getenv("LEVEL2_RELEASE_KEY_ALIAS")
        val keyPassword = (project.findProperty("LEVEL2_RELEASE_KEY_PASSWORD") as String?)
            ?: System.getenv("LEVEL2_RELEASE_KEY_PASSWORD")
        if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                this.storeFile = file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigs.any { it.name == "release" }) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No LEVEL2_RELEASE_* credentials: fall back to the debug key.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        create("staging") {
            initWith(getByName("release"))
            // Minified like release but debug-signed so instrumentation can
            // install alongside; proves R8 keeps on every emulator run.
            // Lenient cross-APK link rules live in staging-rules.pro (tests
            // reference app classes by original name — see that file).
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".staging"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "staging-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Release-only dirty-tree guard: never ship uncommitted state. Staging, debug
// and unit-test builds stay permissive so the normal dev loop is unaffected.
tasks.matching { it.name in listOf("assembleRelease", "bundleRelease") }.configureEach {
    doFirst {
        if (gitDescribe?.endsWith("-dirty") == true) {
            throw GradleException(
                "Refusing release build: working tree is dirty " +
                    "(git describe: $gitDescribe)"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // 1.3.4 pinned: 1.4.x's bindToLifecycle(…, vararg useCases) collides in Kotlin
    // with the 8-arg default-args synthetic facade (internal) — resolution picks
    // the internal one and fails to compile.
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    // CameraX returns Guava `ListenableFuture`s; androidx transitive Guava is
    // otherwise allowed to skew and break ListenableFuture resolution.
    implementation("com.google.guava:guava:33.3.1-android")

    // LiteRT 2.2.0 (single artifact with Interpreter + CompiledModel)
    implementation("com.google.ai.edge.litert:litert:2.2.0")
    // MediaPipe Tasks face detection (BlazeFace short-range model asset)
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}