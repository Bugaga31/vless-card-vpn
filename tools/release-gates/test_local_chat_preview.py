import json
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
class LocalChatPreviewTest(unittest.TestCase):
    def text(self, p): return (ROOT / p).read_text()
    def test_publication_is_separate_and_not_latest(self):
        s = self.text('.github/workflows/local-chat-preview.yml')
        self.assertIn("branches: ['feat/equilibrium-local-chat-169']", s)
        self.assertIn("github.repository == 'Bugaga31/vless-card-vpn'", s)
        self.assertIn('needs: build', s)
        self.assertIn('--prerelease --latest=false', s)
        self.assertIn("test \"$VERSION\" = 'beta-build-169'", s)
        self.assertIn('local-chat-readiness.json', s)
        self.assertIn("'cmake;3.22.1'", s)
        self.assertIn('libequilibrium_ai.so', s)
        for unwanted in ['schedule:', 'pull_request_target:', 'continue-on-error:']:
            self.assertNotIn(unwanted, s)
    def test_no_native_network_or_vpn_initialization_in_ai_process(self):
        app = self.text('app/src/main/java/com/vlesscardvpn/VlessApplication.kt')
        self.assertLess(app.index('process != packageName'), app.index('Store.init(this)'))
        manifest = self.text('app/src/main/AndroidManifest.xml')
        self.assertIn('android:name=".ai.LocalAiService" android:exported="false" android:process=":local_ai"', manifest)
        native = self.text('native/local-ai/CMakeLists.txt')
        self.assertIn('LLAMA_CURL OFF', native); self.assertIn('GGML_OPENMP OFF', native)
    def test_model_does_not_invoke_commands_or_supply_network_success(self):
        service = self.text('app/src/main/java/com/vlesscardvpn/ai/LocalAiService.kt')
        self.assertNotIn('Assistant.run', service)
        self.assertNotIn('Actions.', service)
        self.assertIn('42000', service)
        policy = self.text('app/src/main/java/com/vlesscardvpn/ai/LocalAutoControl.kt')
        self.assertIn('ticket == revision.get()', policy)
        self.assertIn('0..300000', policy)
        self.assertNotIn('checkOk =', policy)
        self.assertIn('preparePolicy(app)', self.text('app/src/main/java/com/vlesscardvpn/core/Actions.kt'))
    def test_download_is_opt_in_exact_size_and_hash(self):
        s = self.text('app/src/main/java/com/vlesscardvpn/ai/LocalAi.kt')
        self.assertIn('count == model.bytes', s)
        self.assertIn('== model.sha256', s)
        self.assertIn('temp.renameTo(f)', s)
        self.assertIn('followSslRedirects(false)', s)
        self.assertIn('temp.delete()', s)
        self.assertIn('withTimeout(45000)', s)
        self.assertNotIn('factsText(', s)
        self.assertIn('SHA-256', self.text('app/src/main/java/com/vlesscardvpn/ui/LocalAiPanel.kt'))
