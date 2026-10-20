rootProject.name = "DSHRemoteMobile"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // mavenLocal()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // mavenLocal()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}

include(":composeApp")
// Android 测试外壳（com.android.application），仅用于把共享 UI 装到设备上跑
include(":androidApp")