plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rollage.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rollage.game"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += setOf("en")

        // Ad IDs are injected by the generator script (see @@ tokens below).
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "AD_BANNER",       "\"ca-app-pub-3940256099942544/6300978111\"")
        buildConfigField("String", "AD_INTERSTITIAL", "\"ca-app-pub-3940256099942544/1033173712\"")
        buildConfigField("String", "AD_REWARDED",     "\"ca-app-pub-3940256099942544/5224354917\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jbox2d:jbox2d-library:2.2.1.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.gms:play-services-ads:23.3.0")
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")
}
