import re,zipfile,os,glob
# Only exception classes, source locations and install result codes leave the diagnostic job.
# No raw log lines, app state, credentials, URLs or device identifiers are published.
rows=[]
def add(x):
 if x not in rows: rows.append(x)
for filename in glob.glob('diagnostic-input/*.zip'):
 with zipfile.ZipFile(filename) as z:
  for name in z.namelist():
   if not name.endswith(('.txt','.log')): continue
   text=z.read(name).decode('utf-8',errors='replace')
   source='Android30' if '(30' in name or 'api-30' in filename else 'Android34' if '(34' in name or 'api-34' in filename else 'release'
   lines=text.splitlines()
   for i,line in enumerate(lines):
    if 'FATAL EXCEPTION' in line:
     add(source+': FATAL EXCEPTION')
     for l in lines[i:i+38]:
      ex=re.search(r'\b((?:java|javax|kotlin|android|androidx|com\.vlesscardvpn|libv2ray)\.[\w.$]*(?:Exception|Error))\b',l)
      if ex:add(source+': '+ex.group(1))
      frame=re.search(r'\bat ([A-Za-z0-9_.$]+)\(([A-Za-z0-9_.$ -]+(?::\d+)?)\)',l)
      if frame:add(source+': at '+frame.group(1)+'('+frame.group(2)+')')
    for code in re.findall(r'\bINSTALL_FAILED_[A-Z_]+\b',line):add(source+': '+code)
    if 'INSTRUMENTATION_FAILED' in line:add(source+': INSTRUMENTATION_FAILED')
    if re.search(r'(?:tests failed|FAILURES!!!)',line,re.I):add(source+': instrumentation failures')
    if 'ALL INSTALL CHECKS OK' in line:add(source+': install checks passed')
if not rows: rows=['No exception stack or installation code found; diagnostic data needs further inspection.']
for i in range(0,min(len(rows),100),15):
 msg='\n'.join(rows[i:i+15]).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
 print('::notice title=Sanitized release diagnosis::'+msg)
