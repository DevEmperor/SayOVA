import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth for the version: versionCode is derived as MAJOR*10000 + MINOR*100 + PATCH,
// so every release only has to bump this string.
val appVersionName = "1.0.0"
val appVersionCode = appVersionName.split(".").map(String::toInt).let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

// Upload-key credentials live in the untracked keystore.properties at the repository root
// (storeFile, storePassword, keyAlias, keyPassword). Without it, release builds are signed with the
// debug key so they can still be installed for testing; Play rejects such bundles, so nothing
// debug-signed can slip into a release by accident.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

// Where the pairing page and its relay live (pair/ in this repository), set as `sayova.pairBaseUrl`
// in gradle.properties. The address is compiled into the app and is the same for the QR code and
// for the watch's own requests.
val pairBaseUrl = (findProperty("sayova.pairBaseUrl") as String?)?.trimEnd('/')
    ?: error("sayova.pairBaseUrl is not set (see gradle.properties)")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "net.devemperor.sayova"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.devemperor.sayova"
        // Wear OS 3 is the oldest release Play still distributes new watch apps to, so this reaches
        // every watch that can install SayOVA at all.
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "PAIR_BASE_URL", "\"$pairBaseUrl\"")
    }

    signingConfigs {
        keystoreProperties?.let { props ->
            create("upload") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The languages SayOVA is translated into; every new string gets all of them. The generated
        // locale config lets the user pick SayOVA's language apart from the watch's.
        localeFilters += listOf("en", "de", "fr", "es", "it", "pt-rBR", "nl", "pl", "sv", "tr", "ja", "ko", "zh-rCN")
        generateLocaleConfig = true
    }

    bundle {
        // Play would otherwise install only the watch's languages from the bundle, and SayOVA's own
        // language setting would then switch to strings that are not there. All 13 together are small.
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    // Heart rate and daily steps from the watch's sensors (watch tools).
    implementation(libs.androidx.health.services.client)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.complications.data.source)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.remote.interactions)

    implementation(libs.qrcodegen)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
