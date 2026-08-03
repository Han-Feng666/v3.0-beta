plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine") 
}

android {
    namespace = "moe.shizuku.server"
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

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")

    implementation(project(":shizuku-fork:aidl"))
    implementation(project(":shizuku-fork:common"))
    implementation(project(":shizuku-fork:shared"))
    compileOnly(project(":shizuku-fork:provider"))
    implementation(project(":shizuku-fork:starter"))
    implementation(project(":shizuku-fork:server-shared"))
    implementation(project(":shizuku-fork:rish"))
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    // Android 9+ 起 IActivityManager/PackageManager 等是 hidden API, server 跑在 app_process 下
    // 没有 application 入口, 不会被主 app 的 hiddenapibypass 自动豁免, 必须 server 自己提早在
    // ShizukuService.onCreate unbypass 一次, 否则崩在 ActivityManager.getService()
    // (典型 stack: Singleton.get → ActivityManager$4.create)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
