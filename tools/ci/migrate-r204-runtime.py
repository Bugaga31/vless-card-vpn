from pathlib import Path
import re
r=Path('app/src/androidTest')
for p in r.rglob('*.kt'):
 s=p.read_text()
 if p.name in ['WarpRuntimeR191Test.kt','FirstConnectRuntimeR190Test.kt','DiscoveryRuntimeR193Test.kt']:
  s=re.sub(r'\b501\b','1001',s).replace('assertEquals(500,AutoLatencyPolicy.MAX_MS)','assertEquals(1000,AutoLatencyPolicy.MAX_MS)').replace('600L,budget','1100L,budget').replace('strictSlotHas600msBudget','strictSlotHas1100msBudget')
 if p.name=='NetworkRuntimeR195Test.kt':s=s.replace('tgMs=700','tgMs=1200')
 if p.name=='DashboardUiR190Test.kt':s=s.replace('else 700','else 1200')
 p.write_text(s)
