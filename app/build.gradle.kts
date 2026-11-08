plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.baotime.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.baotime.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.socket.io.client) {
        exclude(group = "org.json", module = "json")
    }

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockwebserver)
}
