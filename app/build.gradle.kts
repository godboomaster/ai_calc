plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Stable tags map to increasing Android version codes, independently of CI run numbers.
val appVersion = providers.gradleProperty("appVersion").get()
require(Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})""").matches(appVersion)) {
    "appVersion must be MAJOR.MINOR.PATCH without leading zeros or prerelease suffixes"
}
val (major, minor, patch) = appVersion.split('.').map(String::toInt)
val appVersionCode = major * 1_000_000 + minor * 1_000 + patch
require(major <= 2099 && appVersionCode > 0) { "appVersion is outside Android's versionCode range" }

android {
    namespace = "com.smartcalc.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = if (providers.gradleProperty("isolatedDeviceCheck").isPresent) "com.smartcalc.app.importcheck" else "com.smartcalc.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // Формулы LaTeX: кириллица и греческие буквы внутри формул — отдельными шрифтами.
    implementation(libs.jlatexmath.android)
    implementation(libs.jlatexmath.font.cyrillic)
    implementation(libs.jlatexmath.font.greek)
    // Схемы: модель присылает SVG, приложение рисует его само.
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Opt-in experiment only: never packaged in release or enabled in normal builds.
if (providers.gradleProperty("koogProbe").isPresent) {
    require(providers.gradleProperty("isolatedDeviceCheck").isPresent) {
        "Koog probe requires -PisolatedDeviceCheck to protect the main installation"
    }
    android.sourceSets.getByName("androidTest").java.srcDir(
        rootProject.file("experiments/koog-compatibility/network-tests")
    )
    dependencies {
        debugImplementation("ai.koog:prompt-executor-deepseek-client:1.3.0-beta")
        debugImplementation("ai.koog:http-client-ktor:1.3.0")
        debugImplementation("io.ktor:ktor-client-okhttp:3.3.3")
        debugRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
        androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.2.1")
    }
}
