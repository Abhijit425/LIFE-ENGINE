#!/usr/bin/env bash
# Installs the debug APK on the running emulator, launches it, drives a few camera gestures and
# captures screenshots + logcat into ci-out/. Fails if the app crashes or is not running.
set -u
PKG=com.abhijit.lifeengine
OUT=ci-out
mkdir -p "$OUT"
adb wait-for-device
adb shell getprop ro.build.version.release > "$OUT/device.txt"
adb shell wm size >> "$OUT/device.txt"
adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee "$OUT/install.txt"
adb logcat -c
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" 2>&1 | tee "$OUT/start.txt"

shot() { timeout 60 adb exec-out screencap -p > "$OUT/$1.png" || echo "screencap $1 timed out"; }
sleep 20; shot 01_after_20s
sleep 40; shot 02_after_60s
# rotate the camera, zoom out (two-finger pinch is not possible via input; use the overview chip region instead)
adb shell input swipe 1500 900 1000 900 900
sleep 6; shot 03_after_swipe
adb shell input swipe 1300 700 1300 500 900
sleep 6; shot 04_after_tilt

timeout 60 adb logcat -d > "$OUT/logcat.txt"
grep -E "LifeEngine|AndroidRuntime|FATAL|GL error|Compile failed|Link failed" "$OUT/logcat.txt" > "$OUT/app-log.txt" || true
if adb shell pidof "$PKG" > "$OUT/pid.txt"; then
  echo "RUNNING" >> "$OUT/pid.txt"
else
  echo "NOT RUNNING" >> "$OUT/pid.txt"
fi
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt" 2>&1 || true
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1 || true

if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "App crashed"; exit 1
fi
grep -q RUNNING "$OUT/pid.txt" && ! grep -q "NOT RUNNING" "$OUT/pid.txt" || { echo "App not running"; exit 1; }
echo "Smoke test passed"
