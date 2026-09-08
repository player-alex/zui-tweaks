#!/bin/sh
# Objective check of the key-layout module after a reboot.
#
# The questions worth asking, in order of how much they prove:
#   0. did Magisk LOAD the module at this boot?                   (the one that caught it)
#   1. does any layout still map scancode 100 to ALT_RIGHT?       (must be: none)
#   2. which files does Magisk actually have mounted over /system?
#   3. which layout did the attached keyboard resolve to?
#
# Question 0 was added on 2026-08-24 after the module spent a whole boot installed,
# enabled, byte-correct - and not mounted. Every other check reads the *content* of
# /data/adb/modules, which stays perfect whether or not Magisk ever looked at it, so the
# module can be flawless and still do nothing. Magisk prints one line per module it loads
# at post-fs-data, and the absence of that line is the only direct evidence.
#
# Question 2 is the strongest evidence for the end state: a shadowed file comes from
# Magisk's own overlay, an untouched one from the ext4 system partition, so comparing
# st_dev against a file that is certainly ROM settles it without taking anything on trust.

. "$(dirname "$0")/device.sh"   # sets ADB, D, ZT_TMP

cat > "$ZT_TMP/kl-verify.sh" <<'EOF'
#!/system/bin/sh
M=/data/adb/modules/hwkeyboard_langswitch

echo "--- 0. did Magisk load the module at this boot? ---"
if [ ! -d "$M" ]; then
  echo "  NOT INSTALLED - run install-keylayout.sh"
elif grep -q 'hwkeyboard_langswitch: loading module files' /cache/magisk.log 2>/dev/null; then
  echo "  yes"
else
  echo "  NO. Magisk did not load it at this boot, so nothing is mounted."
  echo "  Modules it did load:"
  grep 'loading module files' /cache/magisk.log 2>/dev/null | sed 's/^/    /'
  for m in disable remove skip_mount update; do
    [ -e "$M/$m" ] && echo "  marker present: $M/$m"
  done
  echo "  Enabling a module in the Magisk app only writes a flag - REBOOT to apply."
fi

echo
echo "--- 1. layouts still on ALT_RIGHT (expect none) ---"
grep -l '^key[[:space:]]\{1,\}100[[:space:]]\{1,\}ALT_RIGHT' /system/usr/keylayout/*.kl 2>/dev/null || echo "  none"

echo
echo "--- 2. layouts shadowed by the module (st_dev differing from ROM = shadowed) ---"
ROMDEV=$(stat -c %d /system/build.prop)
echo "  ROM /system is st_dev=$ROMDEV"
for f in "$M"/system/usr/keylayout/*.kl; do
  b=$(basename "$f")
  t=/system/usr/keylayout/$b
  if [ ! -e "$t" ]; then
    printf '  %-30s MISSING from /system - module file never mounted\n' "$b"
  else
    d=$(stat -c %d "$t")
    if [ "$d" = "$ROMDEV" ]; then s="from ROM - NOT shadowed"; else s="st_dev=$d - shadowed"; fi
    printf '  %-30s %-24s %s\n' "$b" "$(grep -m1 '^key 100' "$t")" "$s"
  fi
done

echo
echo "--- 3. module present, superseded one gone ---"
ls /data/adb/modules/
EOF

"$ADB" -s $D push "$ZT_TMP/kl-verify.sh" /data/local/tmp/kl-verify.sh >/dev/null || exit 1
"$ADB" -s $D shell su -c sh /data/local/tmp/kl-verify.sh | tr -d '\r'
"$ADB" -s $D shell rm -f /data/local/tmp/kl-verify.sh
rm -f "$ZT_TMP/kl-verify.sh"

echo
echo "--- 4. attached keyboards and the layout each resolved to ---"
"$ADB" -s $D shell dumpsys input 2>/dev/null | tr -d '\r' | awk '
  /^ *[0-9]+: /    { dev=$0 }
  /Identifier:/    { id=$0 }
  /KeyLayoutFile:/ { if (id ~ /vendor=0x[0-9a-f]/ && id !~ /vendor=0x0000/)
                       { print "  " dev; print "     " $0 } }'
