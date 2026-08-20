plugins {
    id("com.android.application")
}

android {
    namespace = "com.moneyai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moneyai.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
