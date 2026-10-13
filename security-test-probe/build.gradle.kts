plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val sharedSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        include("com/vlesscardvpn/core/LocalSecrets.kt", "com/vlesscardvpn/model/Base64.kt")
    }
    into(layout.buildDirectory.dir("sharedSources"))
}
android {
    namespace = "com.vlesscardvpn.securityprobe"
    compileSdk = 34
    defaultConfig { applicationId = "com.vlesscardvpn.securityprobe"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "test-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("sharedSources"))
}
tasks.named("preBuild").configure { dependsOn(sharedSources) }
