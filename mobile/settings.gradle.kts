rootProject.name = "DSHRemoteMobile"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // HarmonyOS toolchain forks are only available from this repository.
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Keep the fork repository as a fallback for HarmonyOS-only versions.
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}

include(":composeApp")
// Android 测试外壳（com.android.application），仅用于把共享 UI 装到设备上跑
include(":androidApp")
