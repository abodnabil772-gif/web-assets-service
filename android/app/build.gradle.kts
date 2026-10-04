// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme App Build Gradle v8.0
// السيادة الرقمية المطلقة والتجميع العسكري المشفر
// =========================================================================

plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.uranium.fist'
    compileSdk 34

    defaultConfig {
        applicationId "com.uranium.fist"
        minSdk 26
        targetSdk 34
        versionCode 8
        versionName "8.0.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary true
        }
    }

    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            minifyEnabled false
            applicationIdSuffix ".supreme"
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += '/META-INF/{AL2.0,LGPL2.1}'
        }
    }
}

dependencies {
    // الأساسيات العسكرية لأندرويد
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    
    // محرك الاتصالات العسكري المتقدم والمشفر
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
    
    // معالجة المهام والخيوط المتوازية والخدمات الخلفية
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'androidx.work:work-runtime-ktx:2.9.0'
    
    // أدوات التشفير وحماية البيانات المتقدمة
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
}
