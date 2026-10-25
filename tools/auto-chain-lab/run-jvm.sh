#!/usr/bin/env bash
# Isolated compiler/JUnit contract tests, NOT full Android Gradle/CI validation.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPS="${CHAIN_TEST_DEPS:?Set CHAIN_TEST_DEPS to a directory for locked JVM dependencies}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
python3 - "$ROOT/tools/auto-chain-lab/dependencies.lock.json" "$DEPS" <<'PY'
import hashlib,json,sys,urllib.request
from pathlib import Path
lock=json.loads(Path(sys.argv[1]).read_text());root=Path(sys.argv[2]);root.mkdir(parents=True,exist_ok=True)
for item in lock:
    p=root/item['file']
    if not p.exists():
        tmp=p.with_suffix('.part')
        try:
            urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/'+item['maven_path'],tmp)
            if hashlib.sha256(tmp.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Dependency hash mismatch: '+item['file'])
            tmp.replace(p)
        finally:
            tmp.unlink(missing_ok=True)
    if hashlib.sha256(p.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Dependency hash mismatch: '+item['file'])
PY
CP="$DEPS/stdlib.jar:$DEPS/coroutines.jar:$DEPS/junit.jar:$DEPS/json.jar:$DEPS/annotations.jar"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$DEPS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT" \
  "$ROOT/app/src/main/java/com/vlesscardvpn/core/AutoChainSelection.kt" \
  "$ROOT/app/src/main/java/com/vlesscardvpn/core/AutoChainCooldown.kt" \
  "$ROOT/app/src/main/java/com/vlesscardvpn/xray/ChainProbeConfig.kt" \
  "$ROOT/app/src/test/java/com/vlesscardvpn/core/AutoChainSelectionTest.kt" \
  "$ROOT/app/src/test/java/com/vlesscardvpn/core/AutoChainCooldownTest.kt" \
  "$ROOT/app/src/test/java/com/vlesscardvpn/core/ChainProbeConfigTest.kt"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT:$DEPS/*" org.junit.runner.JUnitCore \
  com.vlesscardvpn.core.AutoChainSelectionTest com.vlesscardvpn.core.AutoChainCooldownTest com.vlesscardvpn.core.ChainProbeConfigTest
