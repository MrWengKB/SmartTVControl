plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from the first heading of versions.md, e.g. "# v1.2.3" -> "1.2.3".
// Adding a new heading at the top and pushing to main publishes that version as a release.
val appVersionName: String = run {
    val heading = rootProject.file("versions.md").readLines().firstOrNull { it.trimStart().startsWith("#") }
        ?: error("versions.md has no version heading")
    Regex("""[vV](\d+\.\d+\.\d+)""").find(heading)?.groupValues?.get(1)
        ?: error("First heading of versions.md must contain a version like v1.2.3: $heading")
}
val appVersionCode: Int = run {
    val (major, minor, patch) = appVersionName.split('.').map { it.toInt() }
    require(minor < 1000 && patch < 1000) { "minor and patch must be below 1000" }
    // 1.2.3 -> 1002003, so every new version is an update Android accepts.
    major * 1_000_000 + minor * 1_000 + patch
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
