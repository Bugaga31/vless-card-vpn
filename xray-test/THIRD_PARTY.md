# Third-party components

AndroidLibXrayLite v26.8.20 (2dust and contributors), LGPL-3.0:
https://github.com/2dust/AndroidLibXrayLite/tree/c634d1baea97e94320c0bf6a9cf637369c4f11d4
Official unchanged AAR SHA256: 670cf11d9d10a6bb6548ac4f593acfa4339155732f6f8de4d45923f30a74deed
Library source and build instructions are available at that exact source revision.
Xray-core dependency: 5ca6f4b7d4dc (see the pinned wrapper go.mod), MPL-2.0:
https://github.com/XTLS/Xray-core/tree/5ca6f4b7d4dc

Before distributing a compiled binary, package the LGPL-3.0, GPL-3.0 and MPL-2.0 texts and the corresponding source/licensing notices required by the linked dependencies. This branch is source-only until those release requirements and compilation are verified.
The application's complete source is in this repository. Debug builds can be modified/rebuilt and installed with the developer's own signing key. No library modifications or reverse-engineering restrictions are imposed here.
