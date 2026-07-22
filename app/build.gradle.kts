plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

android {
    namespace = "com.gmail.volkovskiyda.jellyshelf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gmail.volkovskiyda.jellyshelf"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // youtubedl-android bundles a Python runtime per ABI. Ship arm64 only — it covers
        // virtually all modern physical devices and keeps the APK from ballooning across ABIs.
        ndk {
            //noinspection ChromeOsAbiSupport -- arm64 only by design; x86_64 would bundle a second Python runtime.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Install debug and release side by side, and change the launcher icon
            // (debug overrides ic_launcher_foreground in src/debug/res with a "d" badge).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinking/obfuscation. Library consumer rules (Moshi codegen, Retrofit, Room,
            // kotlinx-serialization) come in automatically; app-specific rules live in
            // src/main/keepRules/.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Generates BuildConfig.DEBUG so debug/release-only behaviour (HTTP logging, Timber) keys
        // off the build type instead of a runtime FLAG_DEBUGGABLE check.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // youtubedl-android extracts its bundled Python/binaries from the APK at runtime, which
        // requires the native libraries to be stored uncompressed and extractable.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        checkAllWarnings = true
    }
}

ksp {
    // Check generated Room schemas into app/schemas so version bumps can ship real migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logging.interceptor)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.timber)
    implementation(libs.youtubedl.android.library)
    testImplementation(libs.junit)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}
