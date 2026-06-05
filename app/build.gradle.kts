plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "kittoku.osc"

    compileSdk = 37

    defaultConfig {
        applicationId = "kittoku.osc"
        minSdk = 23
        targetSdk = 36
        versionCode = 49
        versionName = "1.10.2"

        testInstrumentationRunner = ("androidx.test.runner.AndroidJUnitRunner")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
