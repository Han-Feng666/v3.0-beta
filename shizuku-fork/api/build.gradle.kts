plugins {
    id("com.android.library")
}

android {
    namespace = "rikka.shizuku.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.0")
    implementation(project(":shizuku-fork:aidl"))
    implementation(project(":shizuku-fork:shared"))
}
