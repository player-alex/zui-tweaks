#!/bin/sh
# Objective check of the external-display taskbar state.
#   display 13 = external monitor, display 0 = tablet (must stay unchanged)
. "$(dirname "$0")/device.sh"   # sets ADB, D, ZT_TMP

echo "--- Taskbar_dp window (display 13) ---"
"$ADB" -s $D shell dumpsys window windows 2>/dev/null | tr -d '\r' \
  | awk '/Window\{.* Taskbar_dp\}/{f=1} f{print} /^    mPrepareSyncSeqId/{if(f) exit}' \
  | grep -E "mAttrs=|Requested w=|touchable region|mViewVisibility|isVisible|Frames:"

echo "--- navigationBars inset actually applied ---"
"$ADB" -s $D shell dumpsys window displays 2>/dev/null | tr -d '\r' \
  | grep -E "^ +InsetsSource id=.* type=navigationBars" | sort -u

echo "--- internal Taskbar (display 0) must be untouched: expect bottom=1 ---"
"$ADB" -s $D shell dumpsys window windows 2>/dev/null | tr -d '\r' \
  | awk '/Window\{.* Taskbar\}/{f=1} f{print} /^    mPrepareSyncSeqId/{if(f) exit}' \
  | grep -E "mAttrs=|Requested w=|isVisible" | head -3

echo "--- a fullscreen app frame on display 13 (should reach y=1440 when the inset is gone) ---"
"$ADB" -s $D shell dumpsys window windows 2>/dev/null | tr -d '\r' \
  | grep -E "mDisplayId=13|^    Frames:" | grep -A1 "mDisplayId=13" | grep "Frames:" | head -4
