from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class StartHandoff(unittest.TestCase):
    def test_warp_uses_existing_automatic_start_and_preserves_its_log(self):
        source=(ROOT/'tools/e2e-android.sh').read_text()
        case=source.split('if echo "$WR" | grep -q "WARP готов"; then',1)[1].split('else fail "warp:',1)[0]
        self.assertNotIn('e2e_connect',case)
        self.assertNotIn('adb logcat -c',case)
        self.assertIn('[ "$STARTS" -eq 1 ]',case)
        self.assertIn('okprobe "$R"',case)
        self.assertIn('check ok=true',case)
    def test_first_probe_must_be_committed_before_handoff(self):
        source=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
        self.assertIn('if (committed && sameNetwork) onFirst?.invoke(s)',source)
        start=source.index('private suspend fun realTest(')
        end=source.index('/** For each server',start)
        real=source[start:end]
        self.assertLess(real.index('finally { own?.close(); states.flush()'),real.index('onFirst?.invoke(s)'))
        self.assertIn('currentCoroutineContext().ensureActive()',real)
        self.assertIn('started.claimSelected',source)
        self.assertIn('n > 0 && committed',source)
        self.assertIn('AutoEligibility.serviceRank(snapshot.state(it)',source)
if __name__=='__main__':unittest.main()
