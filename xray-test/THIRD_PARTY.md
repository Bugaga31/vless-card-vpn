# Native third-party components and rebuilding

This application uses AndroidLibXrayLite v26.8.20, by 2dust and contributors, under LGPL-3.0. Its use is covered by that license. The unchanged upstream AAR has SHA-256 `670cf11d9d10a6bb6548ac4f593acfa4339155732f6f8de4d45923f30a74deed`.
Source revision: https://github.com/2dust/AndroidLibXrayLite/tree/c634d1baea97e94320c0bf6a9cf637369c4f11d4

The wrapper pins Xray-core revision `5ca6f4b7d4dc`, by XTLS and contributors, under MPL-2.0:
https://github.com/XTLS/Xray-core/tree/5ca6f4b7d4dc
Native transitive dependencies retain their respective notices and licenses.

The release preparation script downloads GPL-3.0, LGPL-3.0 and MPL-2.0 texts from a pinned SPDX revision and copies native dependency license/notice files into `assets/licenses` before APK compilation. It also prepares the pinned wrapper and vendored dependency sources, generated Java binding sources, the complete test-application source, build files and rebuild instructions for the accompanying source ZIP. A binary release must run this preparation; ordinary development builds do not run it automatically.

The source kit permits rebuilding and relinking with a modified native library. Replace the official AAR URL and checksum in the application build script with the modified library or use a local file dependency. Use JDK 17, Android SDK 34 and Gradle 8.7 for the application; native wrapper prerequisites are documented in its README. No restriction is imposed on modifying the library or reverse engineering for debugging modifications. No special signing key is required; a conflicting test installation may need to be removed before installing a build signed with your own key. The original sing-box application's data is not modified.

The release kit includes BUILD_INFO.json with the application and upstream revisions. The application source kit omits the unrelated legacy app and uses standalone Gradle settings; it is not a claim of byte-for-byte reproducible APK signing.
