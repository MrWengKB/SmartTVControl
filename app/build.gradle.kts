import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Single source of truth for the version: version.properties at the repo root.
val appVersionName: String = Properties().run {
    rootProject.file("version.properties").inputStream().use { load(it) }
    getProperty("versionName")
}
val appVersionCode: Int = run {
    val parts = appVersionName.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3 && parts[1] < 1000 && parts[2] < 1000) { "versionName must be MAJOR.MINOR.PATCH" }
    // 1.2.3 -> 1002003, so every bump increases the code Android uses for updates.
    parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
}

android {
    namespace = "com.mrwengkb.weboscontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mrwengkb.weboscontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Release signing comes from environment variables (set as GitHub secrets in CI). Without
    // them the release APK is signed with the debug key so it still installs.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
