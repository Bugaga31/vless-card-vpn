plugins { id("com.android.application") }
android {
    namespace = "com.vlesscardvpn.netprobe"
    compileSdk = 34
    defaultConfig { applicationId = "com.vlesscardvpn.netprobe"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "test-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
