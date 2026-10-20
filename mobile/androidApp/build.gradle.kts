// :androidApp —— 仅用于在 Android 真机/模拟器上跑起共享 UI 的极薄外壳。
//
// 为什么需要单独一个模块：
//   :composeApp 是 KMP 库模块（com.android.library），它同时要产出鸿蒙的 libkn.so，
//   library 模块无法直接 installDebug / 在 Studio 里点 Run。
//   所以这里放一个最小 application 模块，只做 manifest 合并 + 依赖 :composeApp。
//
// Activity 本身（MainActivity）住在 :composeApp 的 androidMain 里，与共享代码同源。
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val appVersion = rootProject.file("../VERSION").readText().trim()

android {
    namespace = "com.dshremote.app.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // 与 :composeApp 的 namespace 区分开，避免和库模块/R 类冲突
        applicationId = "com.dshremote.app.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 40
        versionName = appVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// ============================================================================
// 与 :composeApp 保持一致的依赖改道
//
// resolutionStrategy 只作用于「本模块自己的解析」。:composeApp 对外发布的变体
// 元数据里记录的仍是适配版坐标（1.9.2-0.3.0），所以作为消费方的 :androidApp
// 必须自己再改道一次，否则 fork 的 org.jetbrains.androidx.* 镜像会随 project
// 依赖进入运行时类路径，打包时报 Duplicate class。
// ============================================================================
val forkComposeVersion = libs.versions.composeMultiplatform.get()
val upstreamComposeVersion = forkComposeVersion.substringBeforeLast("-")
val upstreamMaterial3Version = "1.9.0"

configurations.configureEach {
    if (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) {
        resolutionStrategy.eachDependency {
            if (requested.version == forkComposeVersion &&
                requested.group.startsWith("org.jetbrains.compose") &&
                requested.group != "org.jetbrains.compose.export"
            ) {
                if (requested.group == "org.jetbrains.compose.material3") {
                    useVersion(upstreamMaterial3Version)
                } else {
                    useVersion(upstreamComposeVersion)
                }
            }
        }
    }
}

dependencies {
    // 这里不需要 exclude 任何传递依赖。
    //
    // 改道后 compose 工件本身解析到官方 1.9.2，类路径里残留的
    // org.jetbrains.androidx.* 是 KMP 镜像的空壳（androidJvm 变体只有 4KB、
    // 不含任何 .class，真实类来自 Google androidx），二者不会撞类。
    // 所以原先为规避 "Duplicate class androidx.lifecycle.Lifecycle"
    // 而加的 exclude 已可删除。
    implementation(project(":composeApp"))
}
