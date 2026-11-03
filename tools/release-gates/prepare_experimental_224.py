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
        "VLESS-Card-Beta.apk": "VLESS-Card-Beta-1.2.113-experimental.apk",
        "VLESS-Card-Beta-arm64.apk": "VLESS-Card-Beta-1.2.113-experimental-arm64.apk",
    }
    import xml.etree.ElementTree as ET
    paths=list(Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    require(bool(paths),"Missing targeted JVM reports")
    totals={k:0 for k in ("tests","failures","errors","skipped")}
    suites=[]
    for path in paths:
        suite=ET.parse(path).getroot()
        suites.append(suite.get("name"))
        for k in totals: totals[k]+=int(suite.get(k,"0"))
    require(any(n.endswith("AutoRokiR216Test") for n in suites),"New regression suite absent")
    require(any(n.endswith("FlyRokiR216Test") for n in suites),"Fly reservoir regression suite absent")
    require(any(n.endswith("WatsonAutoR217Test") for n in suites),"Watson/queue regressions absent")
    require(any(n.endswith("ServiceMediaR217Test") for n in suites),"Media compatibility regressions absent")
    require(any(n.endswith("AutoFreshR218Test") for n in suites),"Fresh Auto regressions absent")
    require(any(n.endswith("FirstConnectivityR219Test") for n in suites),"First connectivity regressions absent")
    require(any(n.endswith("AssistantIsolationR220Test") for n in suites),"Device isolation regressions absent")
    require(any(n.endswith("R84Test") for n in suites) and any(n.endswith("R85Test") for n in suites),"Original assistant regressions absent")
    require(any(n.endswith("ServiceErrorsR220Test") for n in suites) and any(n.endswith("UiReadabilityR220Test") for n in suites),"Service/contrast regressions absent")
    require(any(n.endswith("ServiceTransportR221Test") for n in suites) and any(n.endswith("ProbeConfigR221Test") for n in suites),"R221 transport/config regressions absent")
    require(any(n.endswith("SeriesDiagnosticsR222Test") for n in suites),"R222 series/diagnostics regressions absent")
    require(any(n.endswith("ServiceExpansionR223Test") for n in suites) and any(n.endswith("AutoProfileR223Test") for n in suites),"R223 service regressions absent")
    require(any(n.endswith("AutoRecoveryR224Test") for n in suites),"R224 background recovery regressions absent")
    require(totals["tests"]>=601 and not any(totals[k] for k in ("failures","errors","skipped")),"Targeted JVM gate failed")
    report={"candidate_sha":sha,"run_id":str(run_id),"version":"1.2.113","version_code":224,"channel":"experimental",
            "targeted_jvm":{"totals":totals,"suites":suites},"functional_checks":{"full_jvm":"not_run","lint":"not_run","emulator":"not_run","physical_device":"not_run","ui":"not_run"},"apks":[],
            "validation":{"status":"TARGETED_JVM_ONLY","thermal_measurement":"not_run"}}
    sums = []
    for old_name, new_name in expected.items():
        apk = root / old_name
        signature = run(str(bt / "apksigner"), "verify", "--min-sdk-version", "21", "--verbose", "--print-certs", str(apk))
        verify_signature(signature)
        run(str(bt / "zipalign"), "-c", "-p", "4", str(apk))
        package = run(str(bt / "aapt2"), "dump", "badging", str(apk)).splitlines()[0]
        verify_package(package, 224)
        manifest = run(str(bt / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml")
        for attr in ("debuggable", "testOnly"):
            require(not re.search(rf"android:{attr}[^\n]*0xffffffff", manifest), "Debug/test-only APK")
        require("networkSecurityConfig" not in manifest, "Unexpected release network security override")
        with zipfile.ZipFile(apk) as archive:
            require(archive.testzip() is None, "Corrupt APK archive")
            for name, want in {"geoip":"5f0ebc505ad5a5b219f9c053ce6228c837669364349a1a70c236192dd99a1c3d", "geoip6":"d6bc775efb8f970afacbb702ea0c80dbf9e2add32232afcfe3f376a972503b56"}.items():
                require(hashlib.sha256(archive.read("assets/"+name)).hexdigest()==want, "Tor GeoIP asset checksum mismatch: "+name)
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
    notice=f"""# Experimental — ограниченная проверка

Проверки JVM по затронутым политикам: {totals['tests']}, failures/errors/skipped = 0. Это не полный набор JVM и не проверка Android/UI или реального телефона. Проверены сборка, постоянная подпись Beta, версия пакета и целостность APK. Main/latest не меняются.

Commit: `{sha}`  
Build: https://github.com/Bugaga31/vless-card-vpn/actions/runs/{run_id}  
Package: `{PACKAGE}`, version 1.2.113, code 224.

"""
    (root / "notes-experimental.md").write_text(notice+docs.read_text())
    print(json.dumps(report,indent=2))



if __name__ == "__main__":
    require(os.environ.get("GITHUB_REPOSITORY") == "Bugaga31/vless-card-vpn", "Wrong repository")
    require(os.environ.get("GITHUB_REF") == "refs/heads/experiment/prerelease-224-release", "Wrong experimental branch")
    prepare(Path("release"), os.environ["CANDIDATE_SHA"], os.environ["GITHUB_RUN_ID"], Path("docs/release-1.2.113.md"))
