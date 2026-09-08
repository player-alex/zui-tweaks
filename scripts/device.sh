#!/bin/sh
# Sourced by every helper in this repo. Sets $ADB, $D (the tablet's adb address) and
# $ZT_TMP (a staging dir for files pushed to the device).
#
# Why this exists - two traps that both fail *silently*, which is the worst kind:
#
#  1. Wireless debugging picks a NEW port at every reboot. Each script used to hardcode
#     `D="<host>:36783"`. After a reboot the tablet moved to a new port (e.g. :45943),
#     so `sh verify-keylayout.sh` was talking to nothing - and its checks printed empty
#     output, which reads exactly like "nothing wrong here". That is how an unmounted
#     Magisk module went unnoticed for nine hours. Resolve the address at run time and
#     fail loudly when the device is not there.
#
#  2. Git Bash rewrites any argument that looks like a POSIX path before handing it to a
#     native .exe, so `adb push x /data/local/tmp/x` becomes
#     `C:/Program Files/Git/data/local/tmp/x` and fails with a misleading
#     "remote secure_mkdirs() failed: No such file or directory". Turning the rewrite off
#     then breaks the *local* side of the same command, because adb.exe cannot resolve a
#     POSIX path either. So: rewrite off, and stage local files under a RELATIVE path,
#     which is never rewritten.
#
# Overrides: ZT_DEVICE=<host:port>, ZT_PRODUCT=<ro.product.device>, ADB=<path to adb.exe>.

# Resolve adb without hardcoding a machine-specific path: honour $ADB, then the
# standard SDK env vars / install locations, then whatever is on PATH.
if [ -z "$ADB" ]; then
  for _c in \
    "$ANDROID_SDK_ROOT/platform-tools/adb" \
    "$ANDROID_HOME/platform-tools/adb" \
    "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" \
    "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -x "$_c" ] && ADB="$_c" && break
  done
  ADB="${ADB:-adb}"
fi
ZT_PRODUCT="${ZT_PRODUCT:-TB323FU}"

# Keep adb's remote paths intact under Git Bash (see trap 2 above).
MSYS_NO_PATHCONV=1
MSYS2_ARG_CONV_EXCL='*'
export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

# Relative on purpose - an absolute POSIX path here would be rewritten.
ZT_TMP=./.stage
mkdir -p "$ZT_TMP"

_zt_attached() {
  "$ADB" devices -l 2>/dev/null | tr -d '\r' |
    awk -v p="product:$ZT_PRODUCT" '$2 == "device" && index($0, p) { print $1; exit }'
}

D="$ZT_DEVICE"
[ -n "$D" ] || D=$(_zt_attached)

if [ -z "$D" ]; then
  # Not attached. The tablet re-advertises itself over mDNS after a reboot, so the new
  # port can be recovered without asking anyone to read it off the screen.
  "$ADB" mdns services 2>/dev/null | tr -d '\r' |
    awk '/_adb-tls-connect/ { print $NF }' |
    while read -r a; do "$ADB" connect "$a" >/dev/null 2>&1; done
  D=$(_zt_attached)
fi

if [ -z "$D" ]; then
  echo "no '$ZT_PRODUCT' device attached - every check below would have passed vacuously." >&2
  echo "  \"$ADB\" devices -l" >&2
  echo "  ZT_DEVICE=<host:port> sh $0     # or connect it first" >&2
  exit 1
fi

echo "device: $D"
