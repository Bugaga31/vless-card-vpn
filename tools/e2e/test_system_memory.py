import unittest
from summarize_system_memory import summarize

class SystemMemorySnapshotTest(unittest.TestCase):
    def test_parses_only_fixed_numeric_fields(self):
        text = '''MemTotal:        2048000 kB\nMemFree:          900000 kB\nMemAvailable:     1200000 kB\nSwapFree:         250000 kB\n--- SYSTEM_SERVER ---\n** MEMINFO in pid 123 [system] **\n Java Heap:     9876\n TOTAL PSS: 22000 TOTAL RSS: 33000\nunsafe uuid vless://secret\n'''
        self.assertEqual({'phase':'before-rotation','mem_total_kb':2048000,'mem_free_kb':900000,'mem_available_kb':1200000,'swap_free_kb':250000,'system_java_heap_kb':9876,'system_total_pss_kb':22000}, summarize(text,'before-rotation'))
    def test_missing_data_is_null_not_zero(self):
        s=summarize('not available','after-rotation-failure')
        self.assertIsNone(s['system_java_heap_kb']);self.assertIsNone(s['mem_available_kb'])
    def test_does_not_parse_another_process_or_injected_values(self):
        s=summarize('MemTotal: 10 kB\n Java Heap: 999\nTOTAL PSS: 800\n--- SYSTEM_SERVER ---\nunknown','before-rotation')
        self.assertIsNone(s['system_java_heap_kb']);self.assertIsNone(s['system_total_pss_kb'])
    def test_unknown_phase_is_not_copied(self):
        self.assertEqual('unknown',summarize('', 'vless://secret')['phase'])

if __name__=='__main__':unittest.main()
