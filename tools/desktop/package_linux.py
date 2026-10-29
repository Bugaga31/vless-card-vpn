from pathlib import Path
import shutil,subprocess
out=Path('desktop-dist');out.mkdir(exist_ok=True)
root=Path('deb-root');app=root/'opt/vless-card';app.mkdir(parents=True,exist_ok=True)
shutil.copy2('desktop-publish/VLESS-Card-Desktop',app/'VLESS-Card-Desktop');(app/'VLESS-Card-Desktop').chmod(0o755)
for name in ['THIRD-PARTY-NOTICES.txt','Xray-LICENSE.txt']:
 shutil.copy2(out/name,app/name)
share=root/'usr/share/applications';share.mkdir(parents=True,exist_ok=True)
(share/'vless-card.desktop').write_text('[Desktop Entry]\nType=Application\nName=VLESS Card Desktop Preview\nComment=Local SOCKS5 proxy, not a system VPN\nExec=/opt/vless-card/VLESS-Card-Desktop\nTerminal=false\nCategories=Network;\n')
control=root/'DEBIAN';control.mkdir(exist_ok=True)
(control/'control').write_text('Package: vless-card-preview\nVersion: 0.3.0\nArchitecture: amd64\nMaintainer: VLESS Card contributors\nDepends: libc6 (>= 2.35), libgcc-s1, libstdc++6, libfontconfig1, libx11-6, libice6, libsm6, zlib1g, libicu70 | libicu74 | libicu76 | libicu78, libssl3 | libssl3t64\nDescription: Experimental local SOCKS5 GUI for VLESS TCP TLS Reality\n Not a system VPN. Linux Mint 21+ x64 preview.\n')
subprocess.run(['dpkg-deb','--root-owner-group','--build',str(root),str(out/'VLESS-Card-Desktop-0.3.0-linux-x64.deb')],check=True)
subprocess.run(['tar','-czf',str(out/'VLESS-Card-Desktop-0.3.0-linux-x64.tar.gz'),'-C',str(root/'opt'),'vless-card'],check=True)
