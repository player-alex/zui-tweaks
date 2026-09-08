#!/system/bin/sh
#
# Derive the key-layout patch from the ROM this module is being flashed onto.
#
# Why derive rather than ship files: a key layout is not portable. Each patched .kl is its
# ROM's own file with one line changed, so shipping this tablet's copies would overwrite
# another device's layouts wholesale - every key on the keyboard, not just right Alt. The
# earlier version of this module did exactly that and was therefore correct on one tablet
# only, and only for as long as that ROM stayed on the same build.
#
# Deriving on device also removes two traps that had to be policed by hand before:
#   - CRLF. The files now come straight off the device, so they are LF by construction.
#     A CRLF .kl makes every keycode label parse as LANGUAGE_SWITCH<CR>, match nothing,
#     and the whole layout silently falls back to stock.
#   - "exactly one line differs". Asserted here per file, by reversing the substitution
#     and checking the result is byte-identical to the original.
#
# What gets patched: every layout, on every partition Android searches, that maps
# scancode 100 (KEY_RIGHTALT) to ALT_RIGHT. Nothing else is touched. Layouts that map it
# to something else, and the gamepads and remotes that never define it, are left alone.
#
# What deliberately does NOT get patched: a vendor layout the ROM does not have. Android
# resolves Vendor_VVVV_Product_PPPP_Version_VVVV.kl, then Vendor_VVVV_Product_PPPP.kl,
# then <device name>.kl, then Generic.kl - so a keyboard with no vendor file of its own
# already lands on the patched Generic.kl. Synthesising one would add a file that is not
# in the ROM, which forces Magisk to shadow the whole directory instead of bind-mounting
# single files, for no behavioural gain.
#
# ---------------------------------------------------------------------------------------
# THE RE-INSTALL PROBLEM, and why the second rule below exists.
#
# Measured on device 2026-08-24: flashing this over a mounted copy of itself found
# **nothing to patch**. Magisk had already shadowed /system/usr/keylayout with the previous
# version, so the scan was reading its own output - every layout said LANGUAGE_SWITCH, none
# said ALT_RIGHT, and the safety check correctly refused to install a module with zero
# files. Safe, but it also meant the module could never be updated in place.
#
# Reading the pristine ROM is not an option: Magisk 30.7 keeps its mirror and worker mounts
# in a separate namespace, and from here /debug_ramdisk/.magisk/mirror is an empty
# directory. Mounting the system block device a second time would work but has to resolve a
# different dm-* device per partition, which is exactly the kind of guessing this file
# exists to avoid.
#
# So: the ROM as this module needs to see it is /system with this module's own contribution
# discounted, and the installed module is the record of what that contribution was. Hence
# two rules rather than one - patch what still says ALT_RIGHT, and carry forward what we
# already patched. A layout that says LANGUAGE_SWITCH and is *not* ours is left alone.

ID=hwkeyboard_langswitch

SUB='s/^\(key[[:space:]]\{1,\}100[[:space:]]\{1,\}\)ALT_RIGHT/\1LANGUAGE_SWITCH/'
UNSUB='s/^\(key[[:space:]]\{1,\}100[[:space:]]\{1,\}\)LANGUAGE_SWITCH/\1ALT_RIGHT/'
IS_ALT='^key[[:space:]]\{1,\}100[[:space:]]\{1,\}ALT_RIGHT'
IS_LANG='^key[[:space:]]\{1,\}100[[:space:]]\{1,\}LANGUAGE_SWITCH'

md5of() { md5sum "$1" | cut -d' ' -f1; }

# Where to read the ROM's layouts from, and where the previously installed copy of this
# module lives. Both are empty/default in production. The test harness under scratchpad
# points them at synthetic directories so this file can be exercised without a device -
# the alternative is finding out whether the sed expressions are right by flashing them.
ROOT="${KL_ROOT:-}"
PREV="${KL_PREV:-/data/adb/modules/$ID}"

ui_print "- Scanning this ROM for layouts that map scancode 100 to ALT_RIGHT"

patched=0
kept=0
scanned=0

for base in /system /vendor /odm /product /system_ext; do
  dir="$ROOT$base/usr/keylayout"
  [ -d "$dir" ] || continue

  # The legacy nesting - $MODPATH/system/vendor rather than $MODPATH/vendor - on purpose.
  # Magisk normalises it onto whichever partition /vendor really is, and that matters
  # because on many ROMs /vendor, /odm and /product are symlinks back under /system.
  case "$base" in
    /system) out="$MODPATH/system/usr/keylayout" ;;
    *)       out="$MODPATH/system$base/usr/keylayout" ;;
  esac
  # Same sub-path inside the module that is already installed, if there is one.
  prevdir="$PREV${out#$MODPATH}"

  for f in "$dir"/*.kl; do
    [ -f "$f" ] || continue
    scanned=$((scanned + 1))
    b=$(basename "$f")

    if grep -q "$IS_ALT" "$f"; then
      mkdir -p "$out"
      sed "$SUB" "$f" > "$out/$b" || abort "! could not write $b"

      # The patch must be exactly the one line. Reversing it has to reproduce the original
      # byte for byte - a stronger check than counting diff lines, and it needs only sed.
      if [ "$(sed "$UNSUB" "$out/$b" | md5sum | cut -d' ' -f1)" != "$(md5of "$f")" ]; then
        abort "! $b changed in more than the one line - refusing to install"
      fi
      if [ "$(md5of "$out/$b")" = "$(md5of "$f")" ]; then
        abort "! $b did not change at all - refusing to install"
      fi

      ui_print "    patch  $base/$b"
      patched=$((patched + 1))

    elif grep -q "$IS_LANG" "$f" && [ -f "$prevdir/$b" ]; then
      # Our own earlier patch, showing through the mount. Carry it forward unchanged.
      mkdir -p "$out"
      cp "$prevdir/$b" "$out/$b" || abort "! could not carry $b forward"
      grep -q "$IS_LANG" "$out/$b" || abort "! carried-forward $b is not patched - refusing"
      ui_print "    keep   $base/$b"
      kept=$((kept + 1))
    fi
  done
done

total=$((patched + kept))
ui_print "- Scanned $scanned layouts: $patched patched, $kept carried forward"

if [ "$total" -eq 0 ]; then
  # One ui_print per line: ui_print writes through Magisk's OUTFD protocol, which is
  # line-oriented, and an embedded newline comes out mangled.
  ui_print "! No layout on this ROM maps scancode 100 to ALT_RIGHT, and none of the"
  ui_print "! layouts already on LANGUAGE_SWITCH came from this module."
  ui_print "! Either right Alt is already something else here, or this ROM keeps its"
  ui_print "! key layouts somewhere non-standard."
  abort "! Nothing was installed."
fi

# A stale flag from an earlier install would leave the module enabled-looking and unmounted.
rm -f "$MODPATH/disable" "$MODPATH/remove" "$MODPATH/skip_mount"
rm -f "$PREV/disable" "$PREV/remove" "$PREV/skip_mount" 2>/dev/null

# Retire the superseded module, if this device still carries it.
[ -d /data/adb/modules/y705_keylayout ] && touch /data/adb/modules/y705_keylayout/remove

set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print "- Reboot to apply. Magisk mounts modules at boot and only at boot."
