# ProGuard / R8 rules for VLESS Card VPN (release builds are minified).

# Project classes: small, and some are referenced by name (manifest aliases, enums restored
# from saved JSON via valueOf, JNI callbacks). Keeping them whole is safe; R8 still strips
# unused library code (material-icons-extended etc.), which is where the size is.
-keep class com.vlesscardvpn.** { *; }

# Xray-core (2dust/AndroidLibXrayLite, gomobile): Go calls back into these via JNI.
-keep class libv2ray.** { *; }
-keep interface libv2ray.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }

# Native methods (hev-socks5-tunnel, byedpi launcher).
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp optional TLS providers that are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MediaPipe LLM Inference («Помощник»): JNI looks classes up by name; protobuf lite messages are reflected.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
