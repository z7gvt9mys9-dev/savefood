plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase's google-services plugin hard-fails the build if google-services.json
// is missing. Apply it only when the file is present so the project still builds
// for contributors without Firebase credentials (FCM simply stays inactive).
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

// API key / secrets are read from gradle properties (e.g. ~/.gradle/gradle.properties
// or -P flags), never committed. Empty default keeps the build green.
fun secret(name: String): String =
    (project.findProperty(name) as String?) ?: ""

android {
    namespace = "kz.savefood.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.savefood.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "YANDEX_MAPKIT_API_KEY",
            "\"${secret("YANDEX_MAPKIT_API_KEY")}\"",
        )
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            // 10.0.2.2 = host loopback from the Android emulator. Override per-machine
            // via -PdevApiBaseUrl=... (e.g. a Cloudflare tunnel URL on a device).
            val devUrl = (project.findProperty("devApiBaseUrl") as String?)
                ?: "http://10.0.2.2:8000"
            buildConfigField("String", "API_BASE_URL", "\"$devUrl\"")
        }
        create("prod") {
            dimension = "env"
            val prodUrl = (project.findProperty("prodApiBaseUrl") as String?)
                ?: "https://api.savefood.kz"
            buildConfigField("String", "API_BASE_URL", "\"$prodUrl\"")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
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
    implementation(libs.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)

    implementation(libs.zxing.android.embedded)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.play.services.location)
    implementation(libs.yandex.maps.mobile)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
