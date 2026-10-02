pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VlessCardVpn"
include(":app")
include(":xray-test")

// Separate UID used only by explicit emulator instrumentation; not shipped in the VPN APK.
include(":vpn-test-probe")
