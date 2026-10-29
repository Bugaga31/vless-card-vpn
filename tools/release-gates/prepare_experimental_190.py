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
        "VLESS-Card-Beta.apk": "VLESS-Card-Beta-1.2.79-experimental.apk",
        "VLESS-Card-Beta-arm64.apk": "VLESS-Card-Beta-1.2.79-experimental-arm64.apk",
    }
    report = {
        "candidate_sha": sha,
        "run_id": str(run_id),
        "version": "1.2.79",
        "version_code": 190,
        "channel": "experimental",
        "functional_checks": {
            "unit_tests": "passed_debug_and_release_matching_sources",
            "lint": "passed_debug",
            "emulator": "passed_56_scoped_native_health_ui_tests_not_full_TUN",
            "physical_device": "not_run",
        },
        "apks": [],
        "validation": {n:json.loads((root / ("validation-"+n+".json")).read_text()) for n in ("jvm","device","ui-review")},
    }
    sums = []
    for old_name, new_name in expected.items():
        apk = root / old_name
        signature = run(str(bt / "apksigner"), "verify", "--min-sdk-version", "21", "--verbose", "--print-certs", str(apk))
        verify_signature(signature)
        run(str(bt / "zipalign"), "-c", "-p", "4", str(apk))
        package = run(str(bt / "aapt2"), "dump", "badging", str(apk)).splitlines()[0]
        verify_package(package, 190)
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
    notice = f"""# Experimental prerelease — gated scoped checks

Executed JVM debug/release, lint, host regressions and 56 controlled Android native/DC/queue/cache/consent/UI tests passed before publication. Source inputs were matched to their reports; physical-device, full TUN and operator DPI checks were NOT performed. See attached scoped validation reports.

Commit: `{sha}`  
Build: https://github.com/Bugaga31/vless-card-vpn/actions/runs/{run_id}  
Package: `{PACKAGE}`, version 1.2.79, code 190.  
Both APKs use the persistent Beta signing certificate. See SHA256SUMS-Beta.txt and verification.json.

---

"""
    (root / "notes-experimental.md").write_text(notice + docs.read_text())


if __name__ == "__main__":
    require(os.environ.get("GITHUB_REPOSITORY") == "Bugaga31/vless-card-vpn", "Wrong repository")
    require(os.environ.get("GITHUB_REF") == "refs/heads/experiment/prerelease-190-release", "Wrong experimental branch")
    prepare(Path("release"), os.environ["CANDIDATE_SHA"], os.environ["GITHUB_RUN_ID"], Path("docs/release-1.2.79.md"))
