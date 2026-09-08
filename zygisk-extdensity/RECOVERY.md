# Recovery — if the Zygisk module breaks boot

The module (`id=zygisk_extdensity`) runs native code inside app processes. It **never** runs in
`system_server` (only `postAppSpecialize` is implemented) and **never** in `adbd` (adbd is a native
init service, not forked from zygote). So even if an app hook crashes the UI, **adb stays alive** and
recovery is one command. Ordered from least to most invasive:

## 1. adb — disable the module, then reboot  (primary, always works)

adbd survives a zygote / system_server / SystemUI crash. As soon as the device is powered on:

```sh
. scripts/device.sh          # resolves $ADB and $D, or fails loudly if nothing is attached

# Disable (keeps the module installed, just not loaded next boot):
"$ADB" -s $D shell su -c 'touch /data/adb/modules/zygisk_extdensity/disable'
# OR remove it entirely:
"$ADB" -s $D shell su -c 'rm -rf /data/adb/modules/zygisk_extdensity'
"$ADB" -s $D shell su -c 'svc power reboot || setprop sys.powerctl reboot'
```

> If the UI never comes up, adb-over-wifi may not have re-associated - a reboot turns the
> **Wireless debugging** toggle off on this ROM, and it picks a new port each time it is turned
> back on. Plug in **USB** and use `wait-for-device` instead: adbd starts before zygote, so USB
> adb is available even at the boot logo. To stop the port moving, `setprop persist.adb.tcp.port
> 5555` (as root) makes adbd listen on a fixed port across reboots; unset it when you are done.

## 2. Magisk Safe Mode  (no PC needed)

Power on; when the **boot animation** starts, **press and hold Volume Down** (some builds: tap it
repeatedly). Magisk boots with **all modules disabled** for that one boot. Then delete/disable the
module from the Magisk app or via adb (step 1), and reboot normally.

## 3. Automatic — Magisk zygisk crash guard

A `.so` that fails to load is skipped by Zygisk per-process; it does not, by itself, loop the boot.
The dangerous case is only a hook that crashes an **early UI** process (SystemUI/launcher). That is
still recoverable by 1 or 2.

## 4. Last resort — recovery / fastboot

Boot to recovery (or `adb reboot recovery`) and remove `/data/adb/modules/zygisk_extdensity`, or in
Magisk app `Modules`. Only needed if 1–3 somehow fail (they should not, because adbd is independent
of zygote).

## What the module deliberately avoids (why boot risk is low)

- **No `postServerSpecialize`** → system_server is never touched.
- Hook install wrapped so a failure is swallowed (the process runs unhooked, never crashes).
- The hook callback is a strict no-op unless the metrics are exactly the external monitor's size at a
  foreign density — normal apps and the internal panel take the original path untouched.
- Built and tested in **stages** (log-only → no-op hook → real correction); each stage is a separate
  install so a regression is pinned to one change and reverted with step 1.
