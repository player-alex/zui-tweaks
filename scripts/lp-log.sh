#!/bin/sh
# Live-tail the drawer long-press probe (LongPressProbeHook, observation only).
#
#   sh scripts/lp-log.sh
#
# Then open the app drawer (앱 목록) and long-press an app icon. Each gesture prints:
#   LP-> [n] showForIcon(BubbleTextView{text=..., surface=AllApps(...), tag=...}) on ...
#   LP-> [n] ZuiItemLongClickListener.a(...) / .b(...)
#   LP-> [n] ItemLongClickListener.beginDrag(...) / LauncherDragController.startDrag(...)
#   LP<- [n] showForIcon => PopupContainerWithArrow{...}   (menu shown)  or  => null
#
# The `surface=` field is the drawer-vs-home discriminator; `tag=` names the app/ItemInfo.
# Enable/disable via /sdcard/zuitweaks.conf (longpressprobe=1|0), then restart the launcher:
#   adb shell am force-stop com.zui.launcher
. "$(dirname "$0")/device.sh"   # sets ADB, D
"$ADB" -s "$D" logcat -c
echo "watching long-press probe on $D — long-press an app in the drawer (Ctrl-C to stop)"
"$ADB" -s "$D" logcat -v brief ZuiTweaks:V '*:S' 2>/dev/null | tr -d '\r' \
  | sed 's/^[A-Z]\/ZuiTweaks *( *[0-9]*): //' \
  | grep --line-buffered -E 'LP->|LP<-|ST->|drawer long-press'
