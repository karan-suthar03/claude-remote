plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.karansuthar.clauderemote"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.karansuthar.clauderemote"
        // Android 8+: covers phones still on Android 11 (e.g. Oppo Reno 6 as shipped).
        minSdk = 26
        targetSdk = 36
        // Release builds from CI set these from the git tag (v1.2.3 -> 1.2.3, code 10203).
        versionCode = (System.getenv("CR_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CR_VERSION_NAME") ?: "0.1"
    }

    // A real release key, when one is provided (CI secrets or ~/.gradle/gradle.properties), so
    // every published APK can update the previous one. Without it, release builds use the
    // local debug key: fine for your own builds, but they can't update an installed release.
    val keystore = System.getenv("CR_KEYSTORE") ?: (findProperty("crKeystore") as String?)
    signingConfigs {
        if (keystore != null && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("CR_KEYSTORE_PASSWORD") ?: findProperty("crKeystorePassword") as String?
                keyAlias = System.getenv("CR_KEY_ALIAS") ?: (findProperty("crKeyAlias") as String?) ?: "claude-remote"
                keyPassword = System.getenv("CR_KEY_PASSWORD") ?: (findProperty("crKeyPassword") as String?)
                    ?: (System.getenv("CR_KEYSTORE_PASSWORD") ?: findProperty("crKeystorePassword") as String?)
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and optimises the code: Compose runs far smoother than in a debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    // WebSocket to the Claude Code bridge.
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
