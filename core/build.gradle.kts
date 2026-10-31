import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything with a rule that can be tested without a watch: the provider protocols, the event model,
// the tools, the cost meter and the pairing crypto. It uses no Android API, so its unit tests run on
// the JVM; it is an Android library only so that it builds with the same AGP-managed Kotlin as :wear.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "net.devemperor.talova.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // OkHttp and serialization types appear in public signatures, so :wear sees them transitively.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
