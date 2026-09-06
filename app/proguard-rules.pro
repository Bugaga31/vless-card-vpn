# ProGuard Rules for VLESS Card VPN

# Project classes
-keep class com.vlesscardvpn.** { *; }

# Sing-box / Libbox JNI & Go Mobile runtime
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }
-keep class go.Seq { *; }

# JSON Serialization for Core configurations
-keep class org.json.** { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}
