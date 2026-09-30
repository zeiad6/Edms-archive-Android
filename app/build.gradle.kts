import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing from env first, then keystore.properties (both outside VCS).
// Every update must be signed with the SAME key, or Android refuses to install it.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signing(env: String, key: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.zidex.edms"
    compileSdk = 35

    signingConfigs {
        signing("EDMS_KEYSTORE", "storeFile")?.let { store ->
            create("release") {
                storeFile = rootProject.file(store)
                storePassword = signing("EDMS_STORE_PASSWORD", "storePassword")
                keyAlias = signing("EDMS_KEY_ALIAS", "keyAlias")
                keyPassword = signing("EDMS_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.zidex.edms"
        minSdk = 26
        targetSdk = 35
        // Keep versionName in step with the desktop release tag (vX.Y.Z):
        // the in-app updater compares it against the latest GitHub release.
        versionCode = 1
        versionName = "1.0.1"
        buildConfigField("String", "UPDATE_REPO", "\"zeiad6/Edms-archive\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    // Document capture: auto-capture, edge detection, perspective correction,
    // ML cleanup (stains/fingers), filters — runs inside Google Play services.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Pairing QR scanner (no camera permission needed — UI lives in Play services).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Barcode on scanned pages → document number (unbundled, model lives in Play services).
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    testImplementation("junit:junit:4.13.2")
}
