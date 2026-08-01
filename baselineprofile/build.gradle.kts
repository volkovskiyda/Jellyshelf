plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.gmail.volkovskiyda.jellyshelf.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    targetProjectPath = ":app"
}

baselineProfile {
    // A physical device, never a managed one: the app APK is arm64-only (youtubedl-android bundles
    // a Python runtime per ABI), so the x86_64 emulator images Gradle-managed devices use cannot
    // install it. This is the same constraint that sends item 06's instrumented suite to Firebase
    // Test Lab. Generation therefore runs over adb against the connected Pixel 5.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
