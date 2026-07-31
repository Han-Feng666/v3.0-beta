plugins {
    id("com.android.library")
    id("dev.rikka.tools.refine") 
}

android {
    namespace = "rikka.shizuku.starter"
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
    implementation(project(":shizuku-fork:common"))
    implementation(project(":shizuku-fork:shared"))
    implementation(project(":shizuku-fork:server-shared"))
    compileOnly(project(":shizuku-fork:provider"))
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
