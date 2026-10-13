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
// Separate UID used only by emulator end-to-end checks; not shipped in the VPN APK.
include(":vpn-test-probe")

// Separate UID; real Android Keystore/migration checks, never shipped in the VPN APK.
include(":security-test-probe")
