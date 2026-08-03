plugins {
    id("com.android.library")
    id("dev.rikka.tools.refine") 
}

android {
    namespace = "rikka.shizuku.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.core:core:1.13.1")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")

    api(project(":shizuku-fork:aidl"))
    api(project(":shizuku-fork:shared"))
    api(project(":shizuku-fork:rish"))

    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
}
