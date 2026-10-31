import java.util.Properties

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
        versionCode = 13
        versionName = "0.8.0"
    }

    // The release key lives outside the repo. Point the "hearth.signing" Gradle property
    // (e.g. in ~/.gradle/gradle.properties) or the HEARTH_SIGNING env var at its keystore.properties.
    // Without it, release builds fall back to the debug key.
    val signingFile = (findProperty("hearth.signing") as String? ?: System.getenv("HEARTH_SIGNING"))
        ?.let { file(it) }
        ?.takeIf { it.isFile }
    val releaseSigning = signingFile?.let { f ->
        val props = Properties().apply { f.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
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
    implementation(libs.androidx.lifecycle.process) // tells the app lock when Hearth goes to the background
    implementation(libs.androidx.biometric) // fingerprint / screen lock prompt for the app lock
    implementation(libs.androidx.fragment)
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
