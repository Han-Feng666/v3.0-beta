plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "rikka.shizuku.provider"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    implementation("androidx.annotation:annotation:1.8.0")
    implementation(project(":shizuku-fork:aidl"))
    implementation(project(":shizuku-fork:shared"))
    implementation(project(":shizuku-fork:api"))
}
