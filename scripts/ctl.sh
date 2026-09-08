#!/bin/sh
# Invoke a TaskbarActivityContext method on the external taskbar and show the result.
#   sh ctl.sh toggleTaskbarStash
#   sh ctl.sh setTaskbarWindowSize i 20
#   sh ctl.sh onSwipeToUnstashTaskbar b true
. "$(dirname "$0")/device.sh"   # sets ADB, D, ZT_TMP
M="$1"; KIND="$2"; VAL="$3"
"$ADB" -s $D logcat -c
case "$KIND" in
  i) "$ADB" -s $D shell am broadcast -a io.laelaps.zuitweaks.CMD --es m "$M" --ei i "$VAL" >/dev/null 2>&1 ;;
  b) "$ADB" -s $D shell am broadcast -a io.laelaps.zuitweaks.CMD --es m "$M" --ez b "$VAL" >/dev/null 2>&1 ;;
  *) "$ADB" -s $D shell am broadcast -a io.laelaps.zuitweaks.CMD --es m "$M" >/dev/null 2>&1 ;;
esac
sleep 2
"$ADB" -s $D logcat -d -v brief ZuiTweaks:V '*:S' 2>/dev/null | sed 's/^[A-Z]\/ZuiTweaks *( *[0-9]*): //'
echo "--- navigationBars inset (display 13 = 3f35xxxx / 13 first row) ---"
"$ADB" -s $D shell dumpsys window displays 2>/dev/null | tr -d '\r' \
  | grep -E "^ +InsetsSource id=.* type=navigationBars" | sort -u
