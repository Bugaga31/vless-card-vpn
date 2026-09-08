plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
val coreFile = layout.buildDirectory.file("verified-core/libv2ray.aar")
val v2rayMobileDir = rootProject.projectDir.resolve("v2ray-mobile")

// Builds the v2ray-core (v2fly) + tun2socks AAR locally via gomobile.
// Replaces the former download of 2dust/AndroidLibXrayLite (Xray-core).
val prepareV2ray by tasks.registering {
    val sources = fileTree(v2rayMobileDir) { include("*.go", "go.mod", "go.sum", "quicstub/**", "build-aar.sh") }
    inputs.files(sources)
    outputs.file(coreFile)
    doLast {
        val dest = coreFile.get().asFile
        dest.parentFile.mkdirs()
        exec {
            workingDir = v2rayMobileDir
            commandLine("bash", "build-aar.sh")
        }
        val built = v2rayMobileDir.resolve("build/libv2ray.aar")
        check(built.isFile) { "gomobile did not produce libv2ray.aar" }
        built.copyTo(dest, overwrite = true)
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
    implementation(files(coreFile).builtBy(prepareV2ray))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
tasks.named("preBuild") { dependsOn(prepareV2ray) }
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
