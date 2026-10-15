pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "yuyi"
// 仅保留安卓应用与其依赖的共享核心。
include(":core", ":app")
