plugins {
    id("com.android.library")
}

android {
    namespace = "rikka.shizuku.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    compileOnly("dev.rikka.hidden:stub:4.4.0")
}
