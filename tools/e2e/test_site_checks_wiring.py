from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
class SiteChecksWiringTest(unittest.TestCase):
    def test_sites_are_required_for_admission_and_evidence(self):
        profile=(ROOT/'app/src/main/java/com/vlesscardvpn/core/AutoCheckProfile.kt').read_text()
        self.assertIn('sites.all { p.siteOk[it]==true }',profile)
        self.assertIn('sites.any {p.siteOk[it]!=true}',profile)
        self.assertIn('|site-https-v1',profile)
    def test_site_checks_use_existing_socks_and_no_redirect_client(self):
        tester=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Tester.kt').read_text()
        self.assertIn('.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))',tester)
        self.assertIn('client.newBuilder().followRedirects(false).followSslRedirects(false).build()',tester)
        self.assertIn('SiteWebProbe.check(domain) {request -> boundedCall(mediaClient,request',tester)
        self.assertIn('ProbeTasks.cancel(tasks)',tester)
        self.assertIn('result.copy(siteOk=siteResults.toMap())',tester)
    def test_ui_selection_and_persistence_are_exercised(self):
        script=(ROOT/'tools/e2e-android.sh').read_text()
        self.assertIn('checks-ui: search did not find GitHub',script)
        self.assertIn('checks-ui: domain or selection not saved',script)
        self.assertIn('checks-ui: selection lost after restart',script)
        self.assertIn('multi-summary.txt',script)
