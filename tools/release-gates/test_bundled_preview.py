import hashlib,io,unittest,zipfile
from pathlib import Path
from verify_bundled_preview import verify_model
ROOT=Path(__file__).resolve().parents[2]
class BundledPreviewTest(unittest.TestCase):
    def apk(self,data,compression=zipfile.ZIP_STORED):
        b=io.BytesIO()
        with zipfile.ZipFile(b,'w',compression=compression) as z:z.writestr('assets/models/qwen05.gguf',data)
        return zipfile.ZipFile(b)
    def test_asset_size_checksum_and_compression_are_gated(self):
        data=b'GGUF-test';sha=hashlib.sha256(data).hexdigest()
        with self.apk(data) as z:verify_model(z,len(data),sha)
        for content,size,want,compression in [(data,99,sha,0),(data,len(data),'0'*64,0),(data,len(data),sha,zipfile.ZIP_DEFLATED)]:
            with self.apk(content,compression) as z, self.assertRaises(RuntimeError):verify_model(z,size,want)
    def test_no_inference_energy_gate_on_file_preparation(self):
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/ai/LocalAi.kt').read_text()
        download=s[s.index('@Synchronized fun download'):s.index('/** User-input actions')]
        self.assertNotIn('if (!readyFor',download)
        self.assertIn('installBundle(app)',download)
        self.assertIn('app.assets.open("models/qwen05.gguf")',s)
        self.assertIn('previous.join()',s)
    def test_experimental_workflow_is_not_a_stable_release(self):
        s=(ROOT/'.github/workflows/bundled-chat-preview.yml').read_text()
        self.assertIn("branches: ['fix/equilibrium-bundled-model-170']",s)
        self.assertIn('--prerelease --latest=false',s)
        self.assertIn('-PbundledAi=true',s)
        self.assertIn('verify_bundled_preview.py',s)
        self.assertNotIn(':app:testReleaseUnitTest',s)
        self.assertNotIn(':app:lintRelease',s)
