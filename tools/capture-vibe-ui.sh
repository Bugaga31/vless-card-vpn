#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
trap 'adb shell settings put system font_scale 1.0; adb shell settings put system accelerometer_rotation 1; adb shell wm size reset; adb shell wm density reset' EXIT
# Capture actual Compose UI, not an HTML approximation. No imported user secrets on this CI emulator.
adb shell wm size reset
adb shell wm density reset
physical_size=$(adb shell wm size | tr -d '\r' | sed -n 's/^Physical size: //p')
physical_width=${physical_size%x*}
physical_height=${physical_size#*x}
density=$(adb shell wm density | tr -d '\r' | sed -n 's/^Physical density: //p')
test -n "$physical_width" && test -n "$physical_height" && test -n "$density"

capture_home() {
	local destination=$1 scale=$2 width_dp=$3
	adb shell settings put system font_scale "$scale"
	adb shell settings put system accelerometer_rotation 0
	if [[ "$width_dp" -gt 0 ]]; then
		adb shell wm size "$((width_dp * density / 160))x$physical_height"
	else
		adb shell wm size reset
	fi
	adb shell am force-stop "$PKG"
	adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
	sleep 6
	bash tools/assert-vibe-foreground.sh
	# At fontScale 2 the status can be below the fold. Prove it is reachable by real
	# bounded scrolling instead of treating a correctly scrollable layout as a crash.
	local reachable=0 xml="ui-$(basename "$destination" .png).xml"
	for attempt in 1 2 3 4 5 6; do
		adb shell uiautomator dump /sdcard/window.xml >/dev/null
		adb shell cat /sdcard/window.xml > "$xml"
		if python3 tools/ui-layout-check.py home "$xml"; then reachable=1; break; fi
		local points
		points=$(python3 tools/ui-layout-check.py scroll "$xml") || exit 1
		read -r x1 y1 x2 y2 <<< "$points"
		adb shell input swipe "$x1" "$y1" "$x2" "$y2" 400
		sleep 1
	done
	test "$reachable" = 1 || { echo "Expected reachable home content for $destination"; exit 1; }
	bash tools/assert-vibe-foreground.sh
	adb exec-out screencap -p > "$destination"
	test -s "$destination"
}

capture_home home-ocean.png 1.0 0
capture_home home-large-text.png 1.3 0
capture_home home-font-scale-2.png 2.0 0
capture_home home-320dp.png 1.0 320
capture_home home-320dp-font-scale-2.png 2.0 320

adb shell settings put system font_scale 1.0
adb shell settings put system user_rotation 1
capture_home home-landscape.png 1.0 0

# Exercise the collapsed/expanded control using its accessibility label, not a guessed screen coordinate.
adb shell settings put system user_rotation 0
capture_home /tmp/home-route-insight-base.png 1.0 0
card_found=0
for scroll_attempt in 1 2 3 4 5 6; do
	adb shell uiautomator dump /sdcard/window.xml >/dev/null
	if grep -q 'Раскрыть объяснение' <<< "$(adb shell cat /sdcard/window.xml)"; then card_found=1; break; fi
	adb shell input swipe "$((physical_width / 2))" "$((physical_height * 4 / 5))" \
		"$((physical_width / 2))" "$((physical_height / 4))" 450
done
test "$card_found" = 1 || { echo 'Route insight card is missing after scrolling'; exit 1; }
point=$(adb shell cat /sdcard/window.xml | python3 -c 'import re,sys,xml.etree.ElementTree as ET; root=ET.fromstring(sys.stdin.read()); node=next((item for item in root.iter("node") if item.get("content-desc")=="Раскрыть объяснение"),None); match=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.get("bounds","") if node is not None else ""); print(f"{(int(match.group(1))+int(match.group(3)))//2} {(int(match.group(2))+int(match.group(4)))//2}" if match else "")')
test -n "$point" || { echo 'Route insight expand control is missing'; exit 1; }
read -r center_x center_y <<< "$point"
adb shell input tap "$center_x" "$center_y"
sleep 1
adb shell uiautomator dump /sdcard/window.xml >/dev/null
grep -q 'Пока нет истории маршрута' <<< "$(adb shell cat /sdcard/window.xml)" || { echo 'Expanded route insight empty state is missing'; exit 1; }
bash tools/assert-vibe-foreground.sh
adb exec-out screencap -p > home-route-insight-expanded.png
test -s home-route-insight-expanded.png
