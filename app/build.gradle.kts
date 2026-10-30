plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.hearth.casaos"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.hearth.casaos"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.6.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed with the debug key so it installs over the earlier debug-signed releases.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The emulator library ships a JNI helper for spawning local processes; the app only talks to remote shells.
        jniLibs { excludes += "**/libtermux.so" }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jsch)
    implementation(libs.termux.terminal.emulator)
    implementation(libs.snakeyaml.engine) // reads and writes the compose YAML behind an app's settings
    implementation(libs.glance.appwidget) // home screen widgets
    implementation(libs.glance.material3)
    implementation(libs.work.runtime) // newer than the one Glance brings, see libs.versions.toml
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sshd.core) // in-process SSH server for the shell tests
}
