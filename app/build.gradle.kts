import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Match the release workflow's v1.0.<run_number> tag.
val releaseNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 59
val betaPreview = providers.gradleProperty("betaPreview").orNull == "true"
val armOnly = betaPreview || providers.gradleProperty("armOnly").orNull == "true"
// -PsplitAbi=true: besides the universal APK, also build one APK per ABI (arm64 is ~2x smaller to download).
val splitAbi = providers.gradleProperty("splitAbi").orNull == "true"
val targetAbis = if (armOnly) listOf("arm64-v8a", "armeabi-v7a") else listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// Xray-core for Android (2dust/AndroidLibXrayLite), pinned by version and SHA-256.
val xrayVersion = "v26.9.30"
val xraySha256 = "cf71680b776b9ca583747ba652f816b047a655eab875d8951e6141636d88bbd6"
val xrayAar = layout.buildDirectory.file("xray/libv2ray-$xrayVersion.aar")
val fetchXray by tasks.registering {
    outputs.file(xrayAar)
    doLast {
        val dest = xrayAar.get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (dest.isFile && sha(dest) == xraySha256) return@doLast
        dest.parentFile.mkdirs()
        val tmp = File(dest.path + ".part")
        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayVersion/libv2ray.aar"
        uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha(tmp) == xraySha256) { "libv2ray.aar checksum mismatch" }
        tmp.renameTo(dest)
    }
}

android {
    namespace = "com.vlesscardvpn"
    compileSdk = 34
    defaultConfig {
        applicationId = if (betaPreview) "com.vlesscardvpn.beta2" else "com.vlesscardvpn"
        manifestPlaceholders["vpnAppLabel"] = if (betaPreview) "VLESS Card · Beta" else "VLESS Card"
        minSdk = 24
        targetSdk = 34
        versionCode = releaseNumber
        versionName = "1.0.$releaseNumber"
        // AGP forbids ndk.abiFilters together with ABI splits, so the split block carries the same list.
        if (!splitAbi) ndk { abiFilters.addAll(targetAbis) }
    }
    splits {
        abi {
            isEnable = splitAbi
            reset()
            include(*targetAbis.toTypedArray())
            isUniversalApk = true
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes {
        release {
            // R8: drops unused code (mostly material-icons-extended) — classes.dex was ~32 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libbyedpi.so"; keepDebugSymbols += "**/libtpws.so" }
        // With ABI splits ndk.abiFilters is off, so drop the x86 libgojni.so that libv2ray.aar ships.
        if (splitAbi && armOnly) jniLibs { excludes += "lib/x86/**"; excludes += "lib/x86_64/**" }
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/byedpi"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/hev"))
}

dependencies {
    implementation(files(xrayAar).builtBy(fetchXray))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

fun sdkDir(): String = System.getenv("ANDROID_HOME") ?: Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}.getProperty("sdk.dir")

// Reproducible source builds; install NDK 27.2.12479018 before building.
val buildByeDpi by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("native/byedpi"))
    inputs.dir(rootProject.file("native/tpws"))
    inputs.file(rootProject.file("native/build-byedpi.sh"))
    inputs.file(rootProject.file("native/launcher.c"))
    inputs.property("armOnly", armOnly)
    outputs.dir(layout.buildDirectory.dir("generated/byedpi"))
    commandLine("bash", rootProject.file("native/build-byedpi.sh").absolutePath,
        "${sdkDir()}/ndk/27.2.12479018", layout.buildDirectory.dir("generated/byedpi").get().asFile.absolutePath,
        if (armOnly) "arm" else "all")
}
val buildHev by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("native/hev-socks5-tunnel"))
    inputs.file(rootProject.file("native/build-hev.sh"))
    inputs.property("armOnly", armOnly)
    outputs.dir(layout.buildDirectory.dir("generated/hev"))
    commandLine("bash", rootProject.file("native/build-hev.sh").absolutePath,
        "${sdkDir()}/ndk/27.2.12479018", layout.buildDirectory.dir("generated/hev").get().asFile.absolutePath,
        if (armOnly) "arm" else "all")
}
tasks.named("preBuild").configure { dependsOn(buildByeDpi, buildHev, fetchXray) }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (System.getenv("GITHUB_ACTIONS") == "true") {
        addTestListener(object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {}
            override fun beforeTest(test: org.gradle.api.tasks.testing.TestDescriptor) {}
            override fun afterTest(test: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
                if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE)
                    println("::error title=JVM test::${test.className}.${test.name}")
            }
            override fun afterSuite(suite: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {}
        })
    }
}
