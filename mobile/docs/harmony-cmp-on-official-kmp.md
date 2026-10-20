# 在官方 KMP / Compose Multiplatform 上集成鸿蒙版 CMP

> 一份 `commonMain` 同时产出 Android APK、桌面 JVM 可执行包和 HarmonyOS HAP —— 而且 Android 与 JVM 走 JetBrains 官方制品，只有鸿蒙侧吃适配版。
>
> 本文记录这条链路从零跑通的全过程，包括三个必须同时满足的隐性条件，以及一路踩出来的坑。

---

## 一、为什么这件事值得单独写一篇

鸿蒙版的 Compose Multiplatform 不是 JetBrains 官方分支，而是第三方（Eazytec，项目名 CPF-KMP-CMP）在官方源码上做的**设计版本 / 适配版**。它通过给坐标打版本后缀的方式发布同名的 `org.jetbrains.compose.*` 工件：

| 组件 | 官方版本 | 适配版版本 |
|---|---|---|
| Compose Gradle 插件 / 运行时 | `1.9.2` | `1.9.2-0.3.0` |
| Kotlin | `2.2.21` | `2.2.21-0.3.0` |
| kotlinx-coroutines | `1.10.2` | `1.10.2-0.3.0` |
| atomicfu | `0.31.0` | `0.31.0-0.3.0` |
| Skiko | — | `0.9.22.2-0.3.0` |

除了 Maven 工件，它还额外提供了三样官方没有的东西：

1. **KMP Gradle 插件里的 `ohosArm64()` 目标** —— 官方 KMP 没有这个 target 函数；
2. **`org.jetbrains.compose.export:export`** —— 把 K/N 编译出的 `libkn.so` 里的符号导出成一组 C API 头文件（`libkn_api.h`），这是 ArkTS 侧能调到 Compose 的唯一入口；
3. **一个 ohpm HAR：`@cpf-kmp-cmp/compose`** —— 提供 `Compose` / `ArkUIViewController` 这两个 ArkTS 组件，以及 `libskikobridge.so`、`libcompose_arkui_utils.so` 两个原生库。

问题就出在这里：**适配版只发布 AndroidJVM 和 native(ohos/ios) 变体，从不发布 jvm(desktop) 工件。** 也就是说，只要你把插件换成适配版，桌面端立刻解析失败；而如果你把 Android 端也一起换成适配版，又会因为 fork 的 `org.jetbrains.androidx.*` 镜像和官方 androidx 撞类而 `Duplicate class`。

于是就有了两个互相拉扯的诉求：

- **插件必须是适配版** —— 否则没有 `ohosArm64()`，也没有 export；
- **Android / JVM 的依赖最好是官方版** —— 否则桌面端没工件，Android 端要去跟 androidx 打架。

这篇要讲的，就是怎么让这两件事同时成立。

---

## 二、总体思路

一句话概括：**插件锁死适配版，依赖声明继续用 `compose.*` 访问器，只在「目标平台的消费类路径」上把版本号重映射回官方。**

```
                     ┌──────────────────────┐
   commonMain  ─────▶│  compose.* 访问器     │  (版本 = 插件版本 = 1.9.2-0.3.0)
   (唯一的 UI 代码)   └──────────┬───────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
   android 配置             jvm 配置                ohos 配置
   改道 → 官方 1.9.2       改道 → 官方 1.9.2       不动 → 适配版 1.9.2-0.3.0
        │                       │                       │
   androidx 体系            runtime-desktop          ohosArm64 + export
        │                       │                       │
        ▼                       ▼                       ▼
   :androidApp APK        .app / dmg / msi        libkn.so + libkn_api.h
                                                        │
                                                        ▼
                                                  harmonyApp HAP
```

关键点：**改道只发生在「消费类路径」上**，即 `*CompileClasspath` / `*RuntimeClasspath`；`metadata*`（commonMain 元数据编译）和 `ohos*`（鸿蒙变体）必须原封不动，否则编译期就找不到鸿蒙侧的实现。

---

## 三、工程骨架

跑通之后，仓库长这样：

```
DSHRemoteMobile/
├── settings.gradle.kts          # 两个 repo：eazytec（适配版）+ gradlePluginPortal
├── gradle/libs.versions.toml    # 版本目录，fork 版本在这里
├── build.gradle.kts             # 根脚本，只声明插件 apply false
├── gradle.properties            # rendererBackend / ohosSkikoVersion
│
├── composeApp/                  # ★ KMP 共享模块（com.android.library）
│   ├── build.gradle.kts         # 三端目标 + 依赖改道
│   └── src/
│       ├── commonMain/          # 共享 UI：App.kt / Platform.kt / Compose Resources
│       ├── androidMain/         # MainActivity + Platform.android.kt
│       ├── jvmMain/             # main.kt（singleWindowApplication）+ Platform.jvm.kt
│       └── ohosMain/            # MainArkUIViewController.kt + Platform.ohos.kt
│
├── androidApp/                  # Android 极薄外壳（com.android.application）
├── harmonyApp/                  # 鸿蒙工程（DevEco Studio 打开的那个）
│   └── entry/
│       ├── libs/arm64-v8a/libkn.so
│       ├── src/main/cpp/        # CMakeLists.txt + napi_init.cpp + include/arm64-v8a/libkn_api.h
│       ├── src/main/ets/pages/Index.ets
│       └── src/main/resources/rawfile/composeResources/<pkg>/...
└── runscript/runOhosApp-Mac.sh  # 一键：KMP 构建 → 同步产物 → hvigor 打 HAP → hdc 安装
```

注意 `:composeApp` 是 **library 模块**（它同时要产出鸿蒙的 `libkn.so`），library 无法直接 `installDebug`、也没法在 Studio 里点 Run。所以单独放一个 `:androidApp` 做 application 外壳，只负责 manifest 合并 + 依赖 `:composeApp`；真正的 `MainActivity` 住在 `:composeApp` 的 `androidMain` 里，与共享代码同源，由清单合并带进 APK。

---

## 四、Step 1：仓库与版本目录

适配版工件发布在一个私有 Nexus 上，插件和依赖都必须能拿到它，所以两处仓库都要配：

```kotlin
// settings.gradle.kts
rootProject.name = "DSHRemoteMobile"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}

include(":composeApp")
include(":androidApp")
```

> `pluginManagement` 少了这行 → 插件本身解析不到（`ohosArm64()` 不存在）；`dependencyResolutionManagement` 少了这行 → 依赖解析不到。两个都得加。

版本目录里，**所有和 Compose 生态强相关的版本都要用适配版**，包括 Kotlin 自己：

```toml
[versions]
agp = "8.6.0"
android-compileSdk = "36"
android-minSdk = "24"
android-targetSdk = "36"

# 这三行是"设计版本"
composeMultiplatform = "1.9.2-0.3.0"
kotlin               = "2.2.21-0.3.0"
kotlinx-coroutines   = "1.10.2-0.3.0"
atomicFu             = "0.31.0-0.3.0"

[libraries]
# fork 独有的 export 工件：K/N sharedLib 的 C API 头文件来自它
compose-multiplatform-export = { module = "org.jetbrains.compose.export:export", version.ref = "composeMultiplatform" }

[plugins]
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler      = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlinMultiplatform  = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

`gradle.properties` 里还有两个鸿蒙专属开关：

```properties
# 渲染模式：fusion-renderer（默认）或 skia
rendererBackend=fusion-renderer
ohosSkikoVersion=0.9.22.2-0.3.0

kotlin.native.cacheKind=none
```

`rendererBackend` 会被 `composeApp/build.gradle.kts` 读走，决定要不要补一长串 `-l` 链接选项（见 Step 3）。

---

## 五、Step 2：`composeApp` 的三端目标

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
}

val forkComposeVersion = libs.versions.composeMultiplatform.get()          // 1.9.2-0.3.0
val upstreamComposeVersion = forkComposeVersion.substringBeforeLast("-")   // 1.9.2
val upstreamMaterial3Version = "1.9.0"                                     // material3 版本线与 CMP 不同步

kotlin {
    // JVM(桌面)：Compose Desktop 工件是 JVM 11 字节码（见下方「坑 4」）
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    // OHOS：只有 fork 插件才有的 target
    listOf(ohosArm64()).forEach { ohosTarget ->
        ohosTarget.binaries.sharedLib {
            baseName = "kn"                            // → libkn.so / libkn_api.h
            export(libs.compose.multiplatform.export)  // ★ 必须 export，否则没有 C API
            linkerOpts("-lz")
            // ...（见 Step 3）
        }
    }
    // ...
}
```

三个 target 的 `jvmTarget` 都锁在 **JVM 11**：

- Compose Desktop 的官方工件本身就是 JVM 11 字节码，若沿用 Kotlin 默认的 1.8，编译「用户代码调用 desktop 工件里的方法」时会报 `Cannot inline bytecode built with JVM target 11`；
- `androidTarget` 与 AGP 的 `compileOptions`（`sourceCompatibility / targetCompatibility = VERSION_11`）保持一致，省掉一堆「Java 版本不一致」的噪音。

---

## 六、Step 3：OHOS 的 sharedLib 与链接选项

这一段是整个鸿蒙侧最容易出玄学问题的地方，值得展开。

```kotlin
ohosTarget.binaries.sharedLib {
    baseName = "kn"
    export(libs.compose.multiplatform.export)
    linkerOpts("-lz")

    val rendererBackend = rootProject.findProperty("rendererBackend")?.toString() ?: "fusion-renderer"
    if (rendererBackend == "fusion-renderer") {
        linkerOpts(
            "-lnative_drawing",   // OH_Drawing_*（字体、绘制）
            "-limage_source",     // OH_ImageSourceNative_*（图像解码）
            "-lpixelmap",         // OH_PixelMap_*
            "-lpixelmap_ndk.z",   // OH_PixelMapNdk_*
            "-lnative_window",    // OH_NativeWindow_*
            "-lace_napi.z",       // N-API
            "-lhilog_ndk.z",      // HiLog 日志
            "-lhitrace_ndk.z",    // HiTrace 性能追踪
            "-luv",               // libuv 事件循环
            "-lunwind",           // 栈展开
            "-licu",              // ICU 文本处理
        )
    }
}
```

为什么要在 `build.gradle.kts` 里手写这一串 `-l`？官方模板的 `NativeTasksConfiguration.kt` 在正常构建时已经会把这些通过 `-l` 写进 `libkn.so` 的 `DT_NEEDED`。但在**增量构建 / 复用了旧产物**的场景下，`DT_NEEDED` 可能不完整，运行时表现为符号找不到或直接崩。把它们在构建脚本里统一补全（而不是散落在 `CMakeLists.txt` 里硬编码），一次配置永久生效。

`ohosMain` source set 也要显式建出来（`src/ohosMain/` 是 arm64 共用的共享目录）：

```kotlin
sourceSets {
    commonMain.dependencies {
        // ★ 必须继续用访问器（见「坑 1」）
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(compose.components.resources)
    }
    jvmMain.dependencies {
        implementation(compose.desktop.currentOs)
    }
    androidMain.dependencies {
        implementation(libs.androidx.activity.compose)
    }

    val ohosMain = sourceSets.create("ohosMain").apply { dependsOn(commonMain.get()) }
    ohosMain.dependencies {
        api(libs.compose.multiplatform.export)
    }
    val ohosArm64Main by getting { dependsOn(ohosMain) }

    commonTest.dependencies { implementation(kotlin("test")) }
}
```

---

## 七、Step 4：`expect` / `actual` 与鸿蒙入口

共享代码只有一个 `expect`：

```kotlin
// commonMain
interface Platform { val name: String }
expect fun getPlatform(): Platform
```

三个 `actual` 分别在 `androidMain` / `jvmMain` / `ohosMain` 里，其中鸿蒙侧只有一行：

```kotlin
// ohosMain/Platform.ohos.kt
class OhosPlatform : Platform {
    override val name: String = "HarmonyOS"
}
actual fun getPlatform(): Platform = OhosPlatform()
```

真正需要写的鸿蒙胶水代码是 **ArkTS 与 Compose 之间的控制器**。它在 Kotlin/Native 侧被编译进 `libkn.so`，再通过 `libkn_api.h` 暴露给 C++，最后由 N-API 挂到 JS 运行时：

```kotlin
// ohosMain/MainArkUIViewController.kt
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("MainArkUIViewController")
fun MainArkUIViewController(env: napi_env): napi_value {
    initMainHandler(env)                    // 把 Kotlin 协程的 Main dispatcher 接到 ArkTS 主线程
    return ComposeArkUIViewController(env) {
        App()                               // ★ 就是 commonMain 里那个 App()
    }
}
```

三个细节：

1. `@CName("MainArkUIViewController")` 决定了 `libkn_api.h` 里生成的 C 函数名；
2. `initMainHandler(env)` 是 fork 版 coroutines 提供的，必须最先调用 —— 否则 Compose 的 recomposition 没有主线程 Handler 可挂；
3. `platform.ArkTS.ArkTS_Napi_NativeModule` 这个 K/N platform lib 也是 fork 版 Kotlin 带的，官方 Kotlin 里没有。

---

## 八、Step 5：依赖改道（全文最核心的 20 行）

到这里 Android 和 JVM 用的还是适配版，桌面端直接挂，Android 端会 `Duplicate class`。核心手段就是这一段：

```kotlin
// composeApp/build.gradle.kts
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
                    useVersion(upstreamMaterial3Version)   // material3 走自己的版本线
                } else {
                    useVersion(upstreamComposeVersion)     // 其余回到 1.9.2
                }
            }
        }
    }
}
```

看着简单，但要让这段代码真正生效，有三个条件是**必须同时满足**的，缺一个就报一个完全不相干的错。

### 条件 1：依赖声明必须继续用 `compose.*` 访问器

一个很自然的想法是「既然要官方版，那就手写官方坐标呗」：

```kotlin
// ✗ 不要这样写
implementation("org.jetbrains.compose.runtime:runtime:1.9.2")
```

实测（用 `--no-configuration-cache` 复现，排除缓存因素）这样写会让 **Compose Resources 的生成源目录注册失效**，编译时报：

```
Unresolved reference 'dshremotemobile'
Unresolved reference 'Res'
```

原因是 `compose.components.resources` 这个访问器不只是在加依赖，它同时触发了 Gradle 插件去注册 `build/generated/compose/resourceGenerator/...` 作为 source root。手写字符串坐标绕过了这个副作用，`Res` 类自然就生成不出来。

**结论：`compose.*` 访问器负责「生成资源代码」，`resolutionStrategy` 负责「换工件版本」，两者分工，不能互相替代。**

> 顺带说一下：`compose.*` 访问器的版本由**插件版本**决定，而插件必须是适配版。所以「默认用官方版、只把 ohos 改道到适配版」这条路（即不去改 `resolutionStrategy`，而是手写官方坐标）是走不通的 —— 这正是访问器方案的必然结果。

### 条件 2：改道范围要按配置名**后缀**匹配，不能按 `contains("android")`

第一版改道写的是：

```kotlin
// ✗ 只覆盖了 jvm，Android 没被改到
if (name.contains("jvm") && !name.contains("android"))
```

后来想扩到 Android，很自然会写成 `contains("android")` —— **但那是错的**。实测列一下真实的配置名：

| 配置名 | 谁在用 | 后缀判据是否命中 |
|---|---|---|
| `debugCompileClasspath` / `releaseCompileClasspath` | **AGP 真正编译时用这个** | ✅（注意：**不含 "android" 前缀**） |
| `androidDebugCompileClasspath` / `androidReleaseCompileClasspath` | KMP 插件内部 | ✅ |
| `jvmCompileClasspath` | 桌面 | ✅ |
| `metadataCommonMainCompileClasspath` | commonMain 元数据编译 | ❌ 被 `!contains("metadata")` 排除 |
| `ohosArm64CompilationDependenciesMetadata` | 鸿蒙 | ❌ 被 `!contains("ohos")` 排除 |

AGP 消费的那个配置**根本不含 "android" 前缀**。用 `contains("android")` 判断会漏掉它。

正确写法是两个条件：**以 `CompileClasspath` / `RuntimeClasspath` 结尾**，且**不含 `ohos` / `metadata`**。

- 排除 `metadata*`：commonMain 的元数据编译必须能看到覆盖 ohos 的变体，把它改到官方版会让 `expect` 找不到鸿蒙实现；
- 排除 `ohos*`：鸿蒙侧必须保持适配版，这是整个方案的立足点。

改完之后可以打印实际解析结果验证：

| 配置 | 解析结果 | 来源 |
|---|---|---|
| `debugCompileClasspath` | `runtime:1.9.2-0.3.0` → `runtime:1.9.2` | 官方 ✅ |
| `jvmCompileClasspath` | `runtime:1.9.2-0.3.0` → `runtime:1.9.2` | 官方 ✅ |
| `metadataCommonMainCompileClasspath` | `runtime:1.9.2-0.3.0` | 适配版 ✅ |
| `ohosArm64CompilationDependenciesMetadata` | `runtime:1.9.2-0.3.0` | 适配版 ✅ |

### 条件 3：**消费方模块必须重复同样的改道**

这条最隐蔽。`resolutionStrategy` 只作用于**本模块自己的解析**。`:composeApp` 对外发布的变体元数据（Gradle Module Metadata）里记录的仍然是适配版坐标 `1.9.2-0.3.0`。所以 `:androidApp` 通过 `implementation(project(":composeApp"))` 拉进来时，解析到的还是 fork 的 `org.jetbrains.androidx.*` 镜像，`Duplicate class` 照旧。

修法是把同一段 `configurations.configureEach { ... }` 原样复制到 `androidApp/build.gradle.kts`：

```kotlin
// androidApp/build.gradle.kts
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
    implementation(project(":composeApp"))
}
```

> 工程上更好的是抽成 `buildSrc` / convention plugin，或者直接 `subprojects { ... }` 统一处理。但在验证阶段先显式复制两份，能让你清楚看到「谁在解析什么」。

### 改道带来的一个额外收益

改道成功后，`Duplicate class` 问题自动消失了。原来 `:androidApp` 上挂着一段 `exclude`：

```kotlin
// 旧写法，现在已不需要
implementation(project(":composeApp")) {
    exclude(group = "org.jetbrains.androidx.lifecycle", module = "lifecycle-common")
}
```

它存在的原因是：fork 的 `org.jetbrains.androidx.lifecycle:lifecycle-common:2.9.4-0.3.0` **只发布了 `jvmApiElements` / `jvmRuntimeElements`（→ `lifecycle-common-jvm`）和 native 变体，唯独没有 `androidJvm` 变体**。正常情况下 Android 端应该命中一个「空壳 android 工件」再转发到 `androidx.lifecycle`；这份 fork 没有发布它，Gradle 就按 `androidJvm ← jvm` 的兼容规则回退到了 `-jvm`，而那份 jar 里是**真实的 `androidx.lifecycle.*` 类**，与 `androidx.activity` 链带入的官方 `androidx.lifecycle` 同名 → 冲突。

改用官方版之后，`org.jetbrains.androidx.lifecycle:lifecycle-common:2.9.5` 是一个**空壳转发**（`-> androidx.lifecycle:lifecycle-common:2.9.4`），冲突的根源没了，`exclude` 自然可以删掉。侧证：Android 类路径上 `org.jetbrains.androidx.*` 条目从 **14 条降到 2 条**。

---

## 九、Step 6：桌面端补齐

桌面端除了依赖改道，还需要三样东西：

```kotlin
// jvmMain 的 actual 实现 + 入口
// src/jvmMain/kotlin/com/mucute/myapplication/main.kt
fun main() {
    singleWindowApplication { App() }
}
```

```kotlin
// composeApp/build.gradle.kts —— 注册桌面应用，这样才有 run / packageDmg 等任务
compose.desktop {
    application {
        mainClass = "com.dshremote.app.MainKt"   // 顶层函数所在文件 → 首字母大写 + Kt
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DSHRemoteMobile"
            packageVersion = "1.0.0"
        }
    }
}
```

注册后可用任务：

```bash
./gradlew :composeApp:run                  # 起窗口直接跑
./gradlew :composeApp:createDistributable  # 生成 .app，不启动
./gradlew :composeApp:packageDmg           # 打 dmg
```

---

## 十、Step 7：鸿蒙工程侧

`harmonyApp/` 是一个标准 DevEco Studio 工程（`compatibleSdkVersion = 6.0.0(20)`、`runtimeOS: HarmonyOS`、`nativeCompiler: BiSheng`），需要改四处。

### 1. 引入 HAR

```json5
// harmonyApp/oh-package.json5
{
  "modelVersion": "6.0.0",
  "dependencies": {
    "@cpf-kmp-cmp/compose": "1.9.2-0.3.0"
  }
}
```

```json5
// harmonyApp/entry/oh-package.json5
{
  "dependencies": {
    "libentry.so": "file:./src/main/cpp/types/libentry",
    "@cpf-kmp-cmp/compose": "1.9.2-0.3.0"
  }
}
```

### 2. CMake 里 `find_package` 这个 HAR

```cmake
# harmonyApp/entry/src/main/cpp/CMakeLists.txt
set(COMPOSE_MODULE_PATH ${MODULE_ROOT_PATH}/oh_modules/@cpf-kmp-cmp/compose)
list(APPEND CMAKE_PREFIX_PATH ${COMPOSE_MODULE_PATH})
find_package(compose REQUIRED CONFIG)        # → 拿到 compose::skikobridge 这个 imported target

set(LIBKN_ABI_DIR "arm64-v8a")
include_directories(${NATIVERENDER_ROOT_PATH}/include/${LIBKN_ABI_DIR})

add_library(entry SHARED napi_init.cpp)

target_link_libraries(entry PUBLIC libace_napi.z.so)
target_link_libraries(entry PUBLIC libhilog_ndk.z.so)
target_link_libraries(entry PUBLIC librawfile.z.so)

# ★ 顺序很重要：先链接 libkn.so，再链接 HAR 里的 skikobridge
target_link_libraries(entry PUBLIC ${NATIVERENDER_ROOT_PATH}/../../../libs/${LIBKN_ABI_DIR}/libkn.so)
target_link_libraries(entry PUBLIC compose::skikobridge)
target_link_libraries(entry PUBLIC ${EGL-lib} ${GLES-lib} ${hilog-lib} ${libace-lib} ${libnapi-lib} ${libuv-lib} libc++_shared.so)
```

> `libkn.so` 和 `libkn_api.h` 必须来自**同一次** K/N link，否则符号名对不上。所以两者放在同一个 `publish*BinariesToHarmonyApp` 任务里一起搬（见 Step 8）。

### 3. N-API 桥接

```cpp
// harmonyApp/entry/src/main/cpp/napi_init.cpp
#include "libkn_api.h"          // ← 由 K/N export 生成

static napi_value MainArkUIViewController(napi_env env, napi_callback_info info) {
    return reinterpret_cast<napi_value>(MainArkUIViewController(env));   // 调 libkn.so 里的函数
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    androidx_compose_ui_arkui_init(env, exports);    // ★ Compose UI 的 ArkUI 后端初始化
    napi_property_descriptor desc[] = {
        {"MainArkUIViewController", nullptr, MainArkUIViewController, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END
```

`androidx_compose_ui_arkui_init` 是 `libkn_api.h` 里的入口，少了它后面 `Compose(...)` 渲染不出东西。

### 4. ArkTS 页面

```ts
// harmonyApp/entry/src/main/ets/pages/Index.ets
import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
import nativeApi from 'libentry.so';

@Entry
@Component
struct Index {
  private controller: ArkUIViewController | undefined = undefined;
  @State errorMessage: string = 'Native module not ready';

  aboutToAppear() {
    try {
      this.controller = nativeApi.MainArkUIViewController();
      if (!this.controller) this.errorMessage = 'Controller creation failed (returned null)';
    } catch (e) {
      this.errorMessage = 'Exception creating controller: ' + JSON.stringify(e);
    }
  }

  build() {
    Column() {
      if (this.controller) {
        Compose({
          controller: this.controller,
          libraryName: 'entry',
          onBackPressed: () => this.controller!.onBackPress()
        })
      } else {
        Text(this.errorMessage)
      }
    }
    .width('100%').height('100%')
  }

  onPageHide() { this.controller?.onPageHide(); }
  onPageShow() { this.controller?.onPageShow(); }
  onBackPressed(): boolean { return this.controller ? this.controller.onBackPress() : false; }
}
```

`aboutToAppear` 里那段 `try/catch` + `errorMessage` 不是凑数的 —— 原生库没加载成功时 `nativeApi.MainArkUIViewController()` 会抛异常，静默失败的话你只会看到一个白屏，根本不知道是 SO 没打包还是符号没导出。

---

## 十一、Step 8：把 Kotlin 产物同步进鸿蒙工程

K/N 编译出来的东西要手动搬到 `harmonyApp/entry/` 的对应位置，一共三类：

```kotlin
// composeApp/build.gradle.kts
val harmonyAppDir: File = run {
    val cliPath = project.findProperty("harmonyAppPath") as String?
    if (cliPath.isNullOrBlank()) rootProject.file("harmonyApp") else file(cliPath)
}

fun String.capitalizeUS(): String =
    this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

arrayOf("debug", "release").forEach { type ->
    tasks.register<Copy>("publish${type.capitalizeUS()}BinariesToHarmonyApp") {
        group = "harmony"
        dependsOn("link${type.capitalizeUS()}SharedOhosArm64")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        into(harmonyAppDir)

        // 1) C 头文件
        from("build/bin/ohosArm64/${type}Shared/libkn_api.h") {
            into("entry/src/main/cpp/include/arm64-v8a/")
        }
        // 2) 共享库
        from(project.file("build/bin/ohosArm64/${type}Shared/libkn.so")) {
            into("entry/libs/arm64-v8a/")
        }
        // 3) Compose Resources（rawfile 形式，供 Res 在鸿蒙端读）
        val composeResourcePackage =
            "${rootProject.name.lowercase()}.${project.name.lowercase()}.generated.resources"
        from("src/commonMain/composeResources") {
            into("entry/src/main/resources/rawfile/composeResources/$composeResourcePackage/")
        }
    }
}
```

`composeResourcePackage` 这行不能想当然：它是 `<rootProject.name.lowercase()>.<moduleName.lowercase()>.generated.resources`，在参数化的项目里很容易和 `Res` 包名对不上。可以直接对照 `commonMain` 里的 import 确认 —— 本项目是 `dshremotemobile.composeapp.generated.resources.*`，跟 `rawfile/composeResources/` 下的目录名一字不差。

> 反过来也要留意：**改 `rootProject.name` 会静默改变这个包名**，于是 `App.kt` 里的 import 和 `rawfile/` 下的目录名都得跟着改，否则资源图静默不显示。本项目从 `MyApplication` 改名为 `DSHRemoteMobile` 时就踩过一次。

---

## 十二、Step 9：一键跑起来

`runscript/runOhosApp-Mac.sh` 把整条链路串起来，核心流程：

```
1. 读 local.properties 拿 OHOS 工程路径             # 可选，默认 harmonyApp
2. 校验 DevEco Studio 版本 >= 6.0.0
3. 从 DEVECO_HOME 导出 PATH：jbr / node / ohpm / hvigor / hdc
4. hdc -t <device> shell aa force-stop <bundle>     # 先停掉旧进程
5. ./gradlew :composeApp:publishDebugBinariesToHarmonyApp
   （或 -m release → publishReleaseBinariesToHarmonyApp）
6. cd harmonyApp && ohpm install --all
7. hvigorw.js assembleHap compileNative
8. hdc install + 拉起 Ability
```

用法：

```bash
./runscript/runOhosApp-Mac.sh                      # 默认 ohosArm64 + 127.0.0.1:5555
./runscript/runOhosApp-Mac.sh -m release           # release 构建
./runscript/runOhosApp-Mac.sh -p /path/to/ohos     # 外置鸿蒙工程
./runscript/runOhosApp-Mac.sh -b com.test.app -a MainAbility
```

脚本支持把鸿蒙工程放在仓库外（`-p`），会转成 `-PharmonyAppPath=...` 传给 Gradle，和 `build.gradle.kts` 里那个 `harmonyAppPath` 属性对接。

---

## 十三、踩坑速查表

| 症状 | 根因 | 处理 |
|---|---|---|
| `Unresolved reference 'Res'` / `'<rootProject.name 小写>'` | 手写了字符串依赖坐标，Compose Resources 的 source root 注册失效 | 改回 `compose.*` 访问器，版本用 `resolutionStrategy` 换 |
| 改了 `rootProject.name` 后资源图不显示 | Compose Resources 包名 = `<rootProject.name.lowercase()>.<模块名.lowercase()>.generated.resources` | 同步改 `App.kt` 的 import 与 `rawfile/composeResources/` 下的目录名 |
| 桌面端解析不到 `runtime-desktop` | 适配版从不发布 jvm(desktop) 工件 | `jvmCompileClasspath` 改道到官方 `1.9.2` |
| Android 端 `Duplicate class androidx.lifecycle.*` | 改道没覆盖 `debugCompileClasspath`（它不含 "android" 前缀） | 改判据为 `endsWith("CompileClasspath")`，排除 `ohos` / `metadata` |
| `:androidApp` 依旧 `Duplicate class` | `resolutionStrategy` 不跨模块，project 依赖携带的是发布方元数据里的适配版坐标 | 消费方模块复制同一段改道；或抽成 convention plugin |
| `expect` 找不到鸿蒙 actual | `metadata*` 配置被误改道 | 判据里显式排除 `contains("metadata")` |
| `Cannot inline bytecode built with JVM target 11` | jvm target 沿用了 Kotlin 默认的 1.8 | `jvm { compilerOptions { jvmTarget = JVM_11 } }` |
| 鸿蒙端白屏、无任何报错 | `libentry.so` 加载失败被静默吞掉 | `aboutToAppear` 包 `try/catch` + `hilog.error` 输出；确认 `androidx_compose_ui_arkui_init` 被调用 |
| 资源图不显示 | `composeResources` 没同步进 `rawfile/`，或目录包名不匹配 | 检查 `publish*BinariesToHarmonyApp` 里的 `composeResourcePackage` |
| 运行时符号缺失 / 崩 | 复用了旧的 `libkn.so`，`DT_NEEDED` 不完整 | 在 `sharedLib` 里按 `rendererBackend` 补全那一串 `-l` |
| `libkn_api.h` 与 `libkn.so` 符号对不上 | 头文件和 SO 来自不同次 link | 用同一个 Copy 任务（`dependsOn(link*SharedOhosArm64)`）一起搬 |

另外记一条工程卫生：`.gitignore` 里的 `/build` 只会忽略根目录，`composeApp/build` 这类子模块产物会漏进来。改成 `**/build/` 之后，再把历史上被误跟踪的产物从索引移除：

```bash
git rm -r --cached composeApp/build      # 只改索引，磁盘文件保留
```

索引规模从 497 降到 75，只剩源码 / 配置 / 资源。

---

## 十四、小结

这套方案的本质是一次**依赖坐标的定向重写**：

- **插件**永远锁在适配版 —— 它是 `ohosArm64()` 和 `export` 的唯一来源，没有替代品；
- **依赖声明**永远走 `compose.*` 访问器 —— 它承担 Compose Resources 的代码生成注册，不能绕过；
- **工件版本**按目标平台分叉 —— Android / JVM 拿到官方制品（干净的 androidx 体系 + 真正的 desktop 工件），OHOS 保留适配版；
- **改道范围**精确到 `*CompileClasspath` / `*RuntimeClasspath` 且排除 `ohos` / `metadata`；
- **每个消费方模块**都要自己改道一次。

跑通之后可以拿这几个任务当回归检查：

```bash
./gradlew :composeApp:compileCommonMainKotlinMetadata   # 元数据（应看到适配版变体）
./gradlew :composeApp:compileKotlinJvm                  # 桌面
./gradlew :composeApp:compileKotlinOhosArm64            # 鸿蒙
./gradlew :composeApp:compileDebugKotlinAndroid         # Android（注意别是 UP-TO-DATE）
./gradlew :androidApp:checkDebugDuplicateClasses        # 冲突检查
./gradlew :androidApp:assembleDebug
```

四端全绿，就说明 `commonMain` 里那份 UI 真的能在三个系统上跑起来了。
