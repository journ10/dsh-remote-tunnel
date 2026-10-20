import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
}

// ============================================================================
// Compose 版本策略：插件锁在鸿蒙适配版，依赖按目标平台改道到官方版
//
// 适配版提供了官方没有的三样东西，插件因此必须用它：
//   1. ohosArm64()                —— OHOS 编译目标
//   2. export 工件                 —— 把 libkn.so 的符号导出为 C API（libkn_api.h）
//   3. @cpf-kmp-cmp/compose HAR   —— ArkTS 侧的 Compose 组件
//
// 但适配版只发布 androidJvm 与 native 变体，从不发布 jvm(desktop) 工件，
// 且它的 org.jetbrains.androidx.* 镜像会与官方 androidx 撞类。
//
// 所以这里继续用 compose.* 访问器声明依赖（其版本 = 插件版本 = 适配版），
// 再靠下方的 configurations.configureEach 按目标平台重映射回官方版本号。
// 访问器不能换成手写坐标 —— 它同时承担 Compose Resources 的生成源目录注册，
// 换成字符串坐标会报 Unresolved reference 'Res'。
// ============================================================================
val forkComposeVersion = libs.versions.composeMultiplatform.get()          // 1.9.2-0.3.0
val upstreamComposeVersion = forkComposeVersion.substringBeforeLast("-")   // 1.9.2
val upstreamMaterial3Version = "1.9.0"                                     // material3 版本线与 CMP 不同步

kotlin {
    // JVM(桌面)：Compose Desktop 工件是 JVM 11 字节码，
    // 若沿用 Kotlin 默认的 1.8 会报 "Cannot inline bytecode built with JVM target 11"。
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Android 目标（与下方 android.compileOptions 对齐到 Java 11）
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // iOS 目标：official Compose Multiplatform iOS
    iosArm64()
    iosSimulatorArm64()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // OHOS（华为鸿蒙）目标
    listOf(
        ohosArm64(),   // 真机 arm64
    ).forEach { ohosTarget ->
        ohosTarget.binaries.sharedLib {
            baseName = "kn"                             // 产物：libkn.so / libkn_api.h
            export(libs.compose.multiplatform.export)   // 必须 export，否则没有 C API
            linkerOpts("-lz")

            // 渲染后端为 fusion-renderer 时补全 libkn.so 的 DT_NEEDED。
            // 正常构建时 NativeTasksConfiguration.kt 已通过 -l 写入这些库；
            // 但复用旧的 libkn.so 时 DT_NEEDED 可能不全，运行期表现为符号缺失。
            // 统一在构建脚本里补齐，避免散落到 CMakeLists.txt。
            val rendererBackend = rootProject.findProperty("rendererBackend")?.toString() ?: "fusion-renderer"
            if (rendererBackend == "fusion-renderer") {
                linkerOpts(
                    "-lnative_drawing",    // OH_Drawing_*（字体、绘制）
                    "-limage_source",      // OH_ImageSourceNative_*（图像解码）
                    "-lpixelmap",          // OH_PixelMap_*
                    "-lpixelmap_ndk.z",    // OH_PixelMapNdk_*
                    "-lnative_window",     // OH_NativeWindow_*
                    "-lace_napi.z",        // N-API
                    "-lhilog_ndk.z",       // HiLog 日志
                    "-lhitrace_ndk.z",     // HiTrace 性能追踪
                    "-luv",                // libuv 事件循环
                    "-lunwind",            // 栈展开
                    "-licu",               // ICU 文本处理
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 必须用访问器声明，理由见文件顶部
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        // 桌面专属 API（application / Window / singleWindowApplication）来自 desktop 工件；
        // currentOs 会按本机平台选 desktop-jvm-macos-arm64 之类的变体。
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        // Android 侧：ComponentActivity / setContent / enableEdgeToEdge 的来源
        androidMain.dependencies {
            implementation("com.journeyapps:zxing-android-embedded:4.3.0")
            implementation("com.google.zxing:core:3.5.3")
            implementation(libs.androidx.activity.compose)
            implementation(libs.okhttp)
        }
        // iOS 共享源码（显式建立，避免 fork 插件未应用默认层级）
        val iosMain = sourceSets.findByName("iosMain") ?: sourceSets.create("iosMain") {
            dependsOn(commonMain.get())
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        iosMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.client.websockets)
        }
        jvmMain.dependencies {
            implementation(libs.okhttp)
        }

        // OHOS 共享源码（src/ohosMain/，arm64 / x64 共用）
        val ohosMain = sourceSets.create("ohosMain").apply {
            dependsOn(commonMain.get())
        }
        ohosMain.dependencies {
            api(libs.compose.multiplatform.export)
        }
        val ohosArm64Main by getting {
            dependsOn(ohosMain)
        }
        // commonTest 需要显式声明，否则 kotlin.test 无法解析
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ============================================================================
// Android 目标配置
// ============================================================================
android {
    namespace = "com.dshremote.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ============================================================================
// 依赖改道：Android + JVM 走 JetBrains 官方版，ohos 保持鸿蒙适配版
//
// 只作用于「目标平台的消费类路径」：
//   - jvm     → 官方桌面工件（适配版不发布 runtime-desktop）
//   - android → 官方 androidx 体系（不再走 org.jetbrains.androidx.* 镜像）
//   - ohos    → 不动，继续用适配版
//
// 两个必须排除的配置类别：
//   metadata* —— commonMain 元数据编译需要看到覆盖 ohos 的变体
//   ohos*     —— 鸿蒙侧保持适配版
//
// 判据用「后缀」而不是 contains("android")：AGP 真正参与编译的配置叫
// debugCompileClasspath / releaseCompileClasspath，并不含 "android" 前缀。
//
// 消费方模块（:androidApp）必须复制同一段逻辑。resolutionStrategy 只作用于
// 本模块的解析，project 依赖携带的是发布方变体元数据里的原始适配版坐标。
// ============================================================================
configurations.configureEach {
    val isTargetClasspath =
        (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) &&
        !name.contains("ohos", ignoreCase = true) &&
        !name.contains("metadata", ignoreCase = true)

    if (isTargetClasspath) {
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

// ============================================================================
// 桌面（JVM）应用配置
//
//   ./gradlew :composeApp:run                 起窗口直接运行
//   ./gradlew :composeApp:createDistributable 生成可执行 .app（不启动）
//   ./gradlew :composeApp:packageDmg          打 dmg 安装包
//
// mainClass 指向 src/jvmMain/kotlin/com.dshremote.app/main.kt。
// 顶层函数所在文件的类名 = 文件名首字母大写 + Kt，故为 MainKt。
// ============================================================================
compose.desktop {
    application {
        mainClass = "com.dshremote.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DSHRemoteMobile"
            packageVersion = "1.0.0"
        }
    }
}

// Harmony App 输出目录（可用 -PharmonyAppPath=... 覆盖）
val harmonyAppDir: File = run {
    val cliPath = project.findProperty("harmonyAppPath") as String?
    if (cliPath.isNullOrBlank()) {
        // 默认：项目根目录 /harmonyApp
        rootProject.file("harmonyApp")
    } else {
        file(cliPath)
    }
}

fun String.capitalizeUS(): String = this.replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
}

// ============================================================================
// 把 K/N 产物同步进 harmonyApp（debug / release 各注册一个任务）
//
//   ./gradlew :composeApp:publishDebugBinariesToHarmonyApp
//
// 三份产物必须一起搬：libkn_api.h 与 libkn.so 来自同一次 link，版本错配会导致
// 符号对不上；composeResources 要以 rawfile 形式供鸿蒙侧的 Res 读取。
// ============================================================================
arrayOf("debug", "release").forEach { type ->
    tasks.register<Copy>("publish${type.capitalizeUS()}BinariesToHarmonyApp") {
        group = "harmony"
        dependsOn("link${type.capitalizeUS()}SharedOhosArm64")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        into(harmonyAppDir)

        from("build/bin/ohosArm64/${type}Shared/libkn_api.h") {          // C API 头文件
            into("entry/src/main/cpp/include/arm64-v8a/")
        }
        from(project.file("build/bin/ohosArm64/${type}Shared/libkn.so")) { // 共享库
            into("entry/libs/arm64-v8a/")
        }

        // Compose Resources 包名 = <rootProject.name>.<模块名>.generated.resources（均小写）。
        // 改动 rootProject.name 会连带改变它，需同步改 App.kt 的 import 与
        // entry/src/main/resources/rawfile/composeResources/ 下的目录名。
        val composeResourcePackage = "${rootProject.name.lowercase()}.${project.name.lowercase()}.generated.resources"
        from("src/commonMain/composeResources") {
            into("entry/src/main/resources/rawfile/composeResources/$composeResourcePackage/")
        }
    }
}
