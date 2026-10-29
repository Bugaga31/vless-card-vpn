import urllib.request,zipfile,hashlib,sys,pathlib,io
platform=sys.argv[1];out=pathlib.Path('desktop-core');out.mkdir(exist_ok=True)
assets={'win-x64':('Xray-windows-64.zip','b17a619343c11b89d8faf36278298856749b15c52277d875d32051b33dcda617','xray.exe'),'linux-x64':('Xray-linux-64.zip','f851110beaff16e78d643f0ccfd9524b4a44dfd59bae3e34bb52bba378f7690e','xray')}
asset,digest,name=assets[platform]
data=urllib.request.urlopen('https://github.com/XTLS/Xray-core/releases/download/v26.9.30/'+asset,timeout=120).read()
assert hashlib.sha256(data).hexdigest()==digest,'Official Xray archive digest mismatch'
with zipfile.ZipFile(io.BytesIO(data)) as z:
 (out/name).write_bytes(z.read(name))
 (out/'Xray-LICENSE.txt').write_bytes(z.read('LICENSE'))
(out/'digest.txt').write_text(hashlib.sha256((out/name).read_bytes()).hexdigest())
(out/'archive-sha256.txt').write_text(digest)

# Include upstream permissive-license notices inside the executable, not just URLs.
notices=pathlib.Path('desktop/App/THIRD-PARTY-NOTICES.txt').read_text()
for url in ['https://raw.githubusercontent.com/AvaloniaUI/Avalonia/11.3.22/licence.md','https://raw.githubusercontent.com/dotnet/runtime/v8.0.24/LICENSE.TXT','https://raw.githubusercontent.com/dotnet/runtime/v8.0.24/THIRD-PARTY-NOTICES.TXT']:
 notices+='\n\n'+url+'\n'+urllib.request.urlopen(url,timeout=60).read().decode('utf-8-sig')
pathlib.Path('desktop/App/THIRD-PARTY-NOTICES.txt').write_text(notices)
