import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val siteUrl = (project.findProperty("SITE_URL") as String?) ?: "https://mesamoz.ir/"
val ksFile: String? = System.getenv("KEYSTORE_FILE")

android {
    // namespace (پکیج کد Kotlin) عمداً همان قبلی است؛ شناسهٔ نصب applicationId است
    namespace = "ir.engpmis.academy"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.mesamoz.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SITE_URL", "\"$siteUrl\"")
        // دامنهٔ سایت برای App Links (باز شدن پیوندهای ایمیل داخل برنامه)
        manifestPlaceholders["siteHost"] = (URI(siteUrl).host ?: "mesamoz.ir")
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
