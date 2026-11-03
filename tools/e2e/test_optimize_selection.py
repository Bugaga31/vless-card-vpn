import unittest
from verify_optimize_selection import verified
class OptimizeProofTest(unittest.TestCase):
    def test_new_change(self):self.assertTrue(verified('optimize result=true dead_selected=false good_selected=3'))
    def test_already_valid(self):self.assertTrue(verified('optimize result=false dead_selected=false good_selected=1'))
    def test_dead_route_never_passes(self):self.assertFalse(verified('optimize result=true dead_selected=true good_selected=3'))
    def test_empty_selection_never_passes(self):self.assertFalse(verified('optimize result=true dead_selected=false good_selected=0'))
    def test_exception_never_passes(self):self.assertFalse(verified('optimize result=error IOException dead_selected=false good_selected=3'))
    def test_old_delta_only_is_not_proof(self):self.assertFalse(verified('optimize result=true'))
