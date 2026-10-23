plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assets.service"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.assets.service"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "11.0"

        // بيسحب المفتاح من GitHub Secrets وقت البناء
        val hmacKey = System.getenv("NODE_HMAC_KEY") ?: "0000000000000000000000000000000000000000000000000000000000000000"
        buildConfigField("String", "NODE_HMAC_KEY", "\"$hmacKey\"")
    }

    buildFeatures {
        buildConfig = true // السطر السحري اللي كان ناقص
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
