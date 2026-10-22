plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val siteUrl = (project.findProperty("SITE_URL") as String?) ?: "https://academy.engpmis.ir/"
val ksFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "ir.engpmis.academy"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.engpmis.academy"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SITE_URL", "\"$siteUrl\"")
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("html") }
}
