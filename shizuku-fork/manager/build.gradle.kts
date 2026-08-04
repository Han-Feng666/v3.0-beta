plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine") 
}

android {
    namespace = "moe.shizuku.manager"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        // native build 配置（boringssl 通过 prefab 拉；libcxx 改用 NDK 自带 c++_static，
        // org.lsposed.libcxx:27.0.12077973 由 LLVM 27 编译与 NDK r26 data layout 不一致会 LTO 报错）
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    // native build 暂时打开（让 NDK 走默认下载）
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":shizuku-fork:aidl"))
    implementation(project(":shizuku-fork:shared"))
    implementation(project(":shizuku-fork:api"))
    implementation(project(":shizuku-fork:provider"))
    implementation(project(":shizuku-fork:server"))
    implementation(project(":shizuku-fork:starter"))
    implementation(project(":shizuku-fork:server-shared"))
    implementation(project(":shizuku-fork:common"))

    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")

    implementation("dev.rikka.rikkax.core:core-ktx:1.4.1")
    implementation("dev.rikka.rikkax.html:html-ktx:1.1.2")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Native build prefab deps
    implementation("io.github.vvb2060.ndk:boringssl:20250114")

    configurations.all {
        resolutionStrategy.force("androidx.core:core:1.13.1", "androidx.core:core-ktx:1.13.1")
    }
}
