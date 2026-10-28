import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from verify_candidate import CERT, PACKAGE, require, verify_package, verify_signature


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def prepare(root, sha, run_id, docs):
    bt = Path(os.environ["ANDROID_HOME"]) / "build-tools/34.0.0"
    expected = {
        "VLESS-Card-Beta.apk": "VLESS-Card-Beta-1.2.70-experimental.apk",
        "VLESS-Card-Beta-arm64.apk": "VLESS-Card-Beta-1.2.70-experimental-arm64.apk",
    }
    report = {
        "candidate_sha": sha,
        "run_id": str(run_id),
        "version": "1.2.70",
        "version_code": 181,
        "channel": "experimental",
        "functional_checks": {
            "unit_tests": "not_run_by_request",
            "lint": "not_run_by_request",
            "emulator": "not_run_by_request",
            "physical_device": "not_run",
        },
        "apks": [],
    }
    sums = []
    for old_name, new_name in expected.items():
        apk = root / old_name
        signature = run(str(bt / "apksigner"), "verify", "--min-sdk-version", "21", "--verbose", "--print-certs", str(apk))
        verify_signature(signature)
        run(str(bt / "zipalign"), "-c", "-p", "4", str(apk))
        package = run(str(bt / "aapt2"), "dump", "badging", str(apk)).splitlines()[0]
        verify_package(package, 181)
        manifest = run(str(bt / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml")
        for attr in ("debuggable", "testOnly"):
            require(not re.search(rf"android:{attr}[^\n]*0xffffffff", manifest), "Debug/test-only APK")
        require("networkSecurityConfig" not in manifest, "Unexpected release network security override")
        with zipfile.ZipFile(apk) as archive:
            require(archive.testzip() is None, "Corrupt APK archive")
            abi = "x86_64" if old_name == "VLESS-Card-Beta.apk" else "arm64-v8a"
            for library in ("libTor.so", "libLyrebird.so", "libgojni.so", "libhev-socks5-tunnel.so"):
                require(f"lib/{abi}/{library}" in archive.namelist(), f"Missing {abi} library: {library}")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        new_path = root / new_name
        apk.rename(new_path)
        report["apks"].append({
            "file": new_name,
            "sha256": digest,
            "certificate_sha256": CERT,
            "package": package,
        })
        sums.append(f"{digest}  {new_name}")
    (root / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    (root / "SHA256SUMS-Beta.txt").write_text("\n".join(sums) + "\n")
    notice = f"""# Experimental prerelease — functional checks skipped

This is an experimental build, not a stable release. Unit tests, lint, emulator checks and physical-device checks were not run by request. The release job compiled the app, signed both APKs, verified the persistent Beta certificate and checked APK package integrity.

Commit: `{sha}`  
Build: https://github.com/Bugaga31/vless-card-vpn/actions/runs/{run_id}  
Package: `{PACKAGE}`, version 1.2.70, code 181.  
The attached `verification.json` and `SHA256SUMS-Beta.txt` identify the exact signed files.

Local passport outcomes are scoped probes, not a guarantee of account login, all Telegram DCs, media, calls, or the full application TUN path. Optional optimization hold never disables emergency healing. All functional checks were omitted by owner request.

---

"""
    (root / "notes-experimental.md").write_text(notice + docs.read_text())


if __name__ == "__main__":
    require(os.environ.get("GITHUB_REPOSITORY") == "Bugaga31/vless-card-vpn", "Wrong repository")
    require(os.environ.get("GITHUB_REF") == "refs/heads/experiment/prerelease-181", "Wrong experimental branch")
    prepare(Path("release"), os.environ["CANDIDATE_SHA"], os.environ["GITHUB_RUN_ID"], Path("docs/release-1.2.70.md"))
