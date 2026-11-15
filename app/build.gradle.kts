plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val signingKeys = listOf("ANDROID_STORE_FILE", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
val signingValues = signingKeys.associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
val hasReleaseSigning = signingValues.values.all { it != null }
if (signingValues.values.any { it != null } && !hasReleaseSigning) {
    throw GradleException("Configuration de signature Android incomplète")
}

android {
    namespace = "app.maillage"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.maillage"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "3.1-exp"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseKey") {
                storeFile = file(signingValues.getValue("ANDROID_STORE_FILE")!!)
                storePassword = signingValues.getValue("ANDROID_STORE_PASSWORD")!!
                keyAlias = signingValues.getValue("ANDROID_KEY_ALIAS")!!
                keyPassword = signingValues.getValue("ANDROID_KEY_PASSWORD")!!
            }
        }
    }
    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("releaseKey")
        }
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.21.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.21.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("info.guardianproject:arti-mobile-ex:1.2.3")
    testImplementation("junit:junit:4.13.2")
}
