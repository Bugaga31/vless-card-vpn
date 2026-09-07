import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
val coreFile = layout.buildDirectory.file("verified-core/libv2ray.aar")
val prepareXray by tasks.registering {
    val expected = "670cf11d9d10a6bb6548ac4f593acfa4339155732f6f8de4d45923f30a74deed"
    inputs.property("sha256", expected)
    outputs.file(coreFile)
    doLast {
        val dest = coreFile.get().asFile
        dest.parentFile.mkdirs()
        val temp = File(dest.parentFile, "download.tmp")
        try {
            val conn = URI("https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.8.20/libv2ray.aar").toURL().openConnection()
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.getInputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(temp.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Xray artifact checksum mismatch: refusing build" }
            temp.copyTo(dest, overwrite = true)
        } finally { temp.delete() }
    }
}
android {
    namespace = "com.vlesscardvpn.xraytest"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.vlesscardvpn.xraytest"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber-xray-test"
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(files(coreFile).builtBy(prepareXray))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.named("preBuild") { dependsOn(prepareXray) }
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
