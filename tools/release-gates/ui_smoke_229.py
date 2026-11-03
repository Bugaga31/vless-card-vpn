#!/usr/bin/env python3
"""Actual emulator rendering of disconnected Home. No fabricated VPN/service PASS."""
import json,subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
OUT=Path("ui-smoke-229");OUT.mkdir(exist_ok=True)
PKG="com.vlesscardvpn.beta2";ACT=PKG+"/com.vlesscardvpn.MainActivity"
def adb(*args,check=True):return subprocess.run(["adb",*args],check=check,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout

def dump(name):
    for i in range(4):
        adb("shell","uiautomator","dump","/sdcard/window.xml",check=False)
        data=adb("shell","cat","/sdcard/window.xml",check=False)
        try:
            root=ET.fromstring(data);(OUT/(name+".xml")).write_bytes(data);return root
        except ET.ParseError:time.sleep(2)
    raise RuntimeError("UI hierarchy unavailable: "+name)

def picture(name): (OUT/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))
def text(root):return " ".join(n.get("text","")+" "+n.get("content-desc","") for n in root.iter("node"))
def target(root,predicate):
    parent={c:p for p in root.iter() for c in p}
    for n in root.iter("node"):
        if predicate(n.get("text","")+n.get("content-desc","")):
            while n.get("clickable")!="true" and n in parent:n=parent[n]
            if n.get("clickable")=="true":return n
    return None

def bounds(n):return list(map(int,re.findall(r"\d+",n.get("bounds",""))))
def tap(n):
    x,y,x2,y2=bounds(n);adb("shell","input","tap",str((x+x2)//2),str((y+y2)//2));time.sleep(2)

def home(name):
    root=dump(name)
    picture(name)
    button=target(root,lambda t:t=="Подключить")
    assert button is not None,"Connect not visible: "+name
    b=bounds(button);assert b[2]-b[0]>=96 and b[3]-b[1]>=96,"Control smaller than 48dp: "+name
    assert button.get("enabled")=="true","Connect disabled: "+name
    assert "Системный TUN создан" not in text(root),"Invented TUN success"
    assert "Отключить" not in text(root),"Invented active session"
    picture(name)
    return {"case":name,"connect_bounds_px":b,"connect_enabled":True,"invented_tun_pass":False}

adb("wait-for-device")
adb("shell","wm","size","640x1280");adb("shell","wm","density","320")
adb("shell","settings","put","global","airplane_mode_on","1")
adb("shell","svc","wifi","disable",check=False);adb("shell","svc","data","disable",check=False)
debug=Path("app/build/outputs/apk/debug/app-universal-debug.apk")
assert debug.is_file(),"Missing universal debug APK"
adb("install","-r","-g",str(debug))
rows=[]
for theme in ("light","dark","amoled"):
    for scale in (1.0,2.0):
        name=f"{theme}-font-{scale:g}"
        adb("shell","am","force-stop",PKG)
        adb("shell","settings","put","system","font_scale",str(scale))
        fixture={"servers":[],"states":{},"settings":{"rev":999,"mode":"AUTO","theme":theme,
            "subscriptions":[],"autoUpdateSubs":False,"lastSubRefresh":4102444800000,
            "welcomeSound":False,"oceanBackground":False,"oceanMotion":False,"reducedMotion":True,
            "secureScreen":False,"autoChainSearch":False,"torAuto":False,"phoneLearning":False,
            "autoChecks":["telegram","stability","youtube","discord","voice","instagram"],
            "autoLatencyPresetVersion":227,"probeParallel":5}}
        f=OUT/"state.json";f.write_text(json.dumps(fixture));adb("push",str(f),"/data/local/tmp/ui-state.json")
        adb("shell",f"run-as {PKG} sh -c 'mkdir -p files && cp /data/local/tmp/ui-state.json files/state.json'")
        adb("shell","am","start","-W","-n",ACT,"--ez","e2e_reload","true");time.sleep(4)
        rows.append(home(name))
        # Exercise the real profile bottom sheet through UI semantics and a scrollable Home.
        profile=None
        for step in range(8):
            root=dump(name+"-scroll")
            profile=target(root,lambda t:t.startswith("Профиль Авто:"))
            if profile is not None:break
            adb("shell","input","swipe","320","1000","320","390","350");time.sleep(1)
        assert profile is not None,"Profile control unreachable: "+name
        picture(name+"-profile-control");tap(profile)
        root=dump(name+"-sheet");assert "Профиль проверок Авто" in text(root),"Profile sheet did not open"
        picture(name+"-sheet");adb("shell","input","keyevent","4");time.sleep(1)
# Fresh installed production-signed release launch, not a claim about an upgrade or a live TUN.
adb("uninstall",PKG)
adb("shell","settings","put","system","font_scale","1.0")
release=Path("release/VLESS-Card-Beta-arm64.apk")
# The emulator is x86_64, so use the signed universal release.
release=Path("release/VLESS-Card-Beta.apk");assert release.is_file()
adb("install","-g",str(release));adb("shell","am","start","-W","-n",ACT);time.sleep(5)
rows.append(home("signed-release-fresh-launch"))
summary={"status":"PASS","api":33,"width_dp":320,"height_dp":640,"debug_theme_font_cases":6,
    "signed_release_fresh_launch":True,"rows":rows,"scope":"disconnected Home render and profile sheet; no VPN, handoff, native-restart, call or physical-phone validation"}
(OUT/"summary.json").write_text(json.dumps(summary,indent=2))
print(json.dumps(summary))
