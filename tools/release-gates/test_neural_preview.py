import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class NeuralPreviewTest(unittest.TestCase):
    def test_only_separate_non_latest_prerelease(self):
        workflow = (ROOT / '.github/workflows/neural-preview.yml').read_text()
        self.assertIn("branches: ['feat/equilibrium-redesign-168']", workflow)
        self.assertIn("github.repository == 'Bugaga31/vless-card-vpn'", workflow)
        self.assertIn('--prerelease --latest=false', workflow)
        self.assertNotIn('--latest \\', workflow)
        self.assertNotIn('--draft', workflow)
        self.assertNotIn('release-build-*) gh release', workflow)
        self.assertIn('needs: build', workflow)
        self.assertIn('neural-preview-readiness.json', workflow)
    def test_fast_preview_is_explicit_and_stable_workflow_keeps_quality(self):
        workflow = (ROOT / '.github/workflows/neural-preview.yml').read_text()
        self.assertNotIn('emulator-install:', workflow)
        self.assertIn('НЕ запускались', workflow)
        stable = (ROOT / '.github/workflows/beta-release.yml').read_text()
        self.assertIn('needs: quality', stable)
        self.assertIn('needs: [build, emulator-install]', stable)
    def test_existing_identity_is_preserved(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('"com.vlesscardvpn.beta2" else "com.vlesscardvpn"', gradle)
    def test_tickets_are_before_probe_and_cancellation_guarded(self):
        source = (ROOT / 'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
        self.assertLess(source.index('val neuralTickets'), source.index('Tester.real(variants'))
        self.assertIn('neuralContext.ensureActive()', source)
        self.assertIn('neuralConfirmContext.ensureActive()', source)
    def test_search_never_passes_facts_or_autoruns(self):
        source = (ROOT / 'app/src/main/java/com/vlesscardvpn/core/AssistantWebSearch.kt').read_text()
        self.assertNotIn('facts(', source.replace('facts()', ''))
        self.assertNotIn('HttpClient', source)
        self.assertNotIn('TunnelService', source)

if __name__ == '__main__':
    unittest.main()
