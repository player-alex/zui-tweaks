# ZUI Tweaks

LSPosed module of device tweaks for a Lenovo TB323FU (ZUI 18 / Android 16).

**Taskbar** - auto-hides the taskbar on an external display and gives the screen space
back to apps.
**Icons** - pre-scales the external taskbar's app icons so they are blitted 1:1 instead of
being minified 2.87x in a single bilinear tap.
**Keyboard** - a Magisk key-layout module (`magisk/hwkeyboard_langswitch`) so the 한/영 key
stops eating the character typed after it, on every keyboard attached to the tablet. It
is carried inside the APK and installed from the app.
**Home guard** - if ZUI's PC-mode display switching strands the external display's home on
the tablet's own screen (wallpaper only, no taskbar), it puts the real tablet home back
automatically, so the tablet's bottom bar never goes missing.

All of it is configured from the app itself - open **ZUI Tweaks** from the launcher.
Setting it up needs no terminal.

The bar collapses out of sight; moving the mouse to the bottom edge brings it back;
it collapses again a moment after the cursor leaves. The tablet's own taskbar is
untouched.

## How it works

The external bar is **not** SystemUI's navigation bar - it is the ZUI launcher's
`Taskbar_dp` window (`com.zui.launcher`, a Launcher3 fork). So the module hooks the
launcher, not SystemUI, which also means a mistake here costs a launcher restart
rather than a boot loop. Every number below is a measurement off the device, not a guess -
the reconnaissance notes behind them are kept out of this repo, but each section says what
was measured and how.

| Piece | What it does |
|---|---|
| `TransientTaskbarHook` | Finds the external taskbar (`TaskbarActivityContextDp`, the subclass ZUI uses only for external displays). Optionally switches it to Launcher3's transient mode - off by default, see below. |
| `AutoHideController` | Collapse/expand via `TaskbarActivityContext.setTaskbarWindowSize(int)`, the launcher's own API. Cursor tracking hooks whichever class actually declares `dispatchHoverEvent` for the drag layer (`ViewGroup`, not `View`), and forces `TOUCHABLE_INSETS_FRAME` while collapsed so the 2px window still receives the cursor. |
| `InsetsPatcher` | Zeroes `providedInsets` (and every `paramsForRotation` copy) on the `Taskbar_dp` window, so apps get the full 1440px. Re-applied on `updateViewLayout`, because the launcher rebuilds them on every resize. |
| `SecondaryHomeGuard` | Hooks `SecondaryDisplayLauncher.onResume()`; if the external display's home ever resumes on the tablet's own display (0), finishes it and relaunches the real primary home there. Works around a ZUI PC-mode bug that otherwise leaves the tablet on wallpaper with no taskbar until a reboot. |
| `Control` | Broadcast receiver living inside the launcher. Two jobs: poking the live taskbar from adb (diagnostics), and answering the settings app's ordered broadcast that reads and restores drawer folders. Registers on `Application.onCreate`, so it exists whether or not a taskbar or drawer has appeared. |
| `StashHook` | Forces Launcher3's stash guards open. Off by default - the stash state machine does not engage on this build. |

Launcher3's own stash mechanism looked like the natural fit and mostly is not usable
here: the flags flip correctly but `isStashed()` never becomes true on this build.
`setTaskbarWindowSize` does the same job and is what the module uses.

## Build and install

One APK, and it carries both Magisk modules.

| artefact | what it is | how it is installed |
|---|---|---|
| `app-debug.apk` | the LSPosed module **and** the settings app | `adb install -r`, or any package installer. Then enable it in LSPosed, set its scope, reopen it |
| `app-release.apk` | the same, minus the diagnostic-only hooks | `./gradlew assembleRelease`. Signed if this machine has a `keystore.properties`, otherwise it comes out unsigned and will not install - see below |
| `hwkeyboard_langswitch.zip` | the Magisk key-layout module | from **inside the app** - the *Keyboard fix* card at the top. The zip is an asset of the APK, flashed through `magisk --install-module` |
| `zygisk_extdensity.zip` | the Zygisk external-display density module | from **inside the app** - the module card in the *Misc* section. Also an asset of the APK, packed from `magisk/zygisk_extdensity/` |

They stay independent at runtime: the key-layout module keeps working with the app
uninstalled, and the taskbar hooks do not care whether it is present. What changed is how
it gets there. It used to be a bare directory pushed by a shell script; it is now a proper
flashable zip that the app installs, and the app reads back whether Magisk actually
mounted it.

Nothing below needs a terminal except building from source. The `.sh` files in `scripts/`
are measurement and cross-check tools, not setup steps.

The APK build never runs the NDK: both Magisk modules are packed from `magisk/` as they stand, and
the Zygisk module's `.so` is a checked-in snapshot. Rebuilding *that* is a separate, deliberate step
- see `zygisk-extdensity/README.md`.

### Signing a release build

`assembleRelease` signs the APK itself when the repo root has a **`keystore.properties`**:

```properties
storeFile=/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

That file is gitignored, and so is every keystore format (`*.jks`, `*.keystore`, `*.p12`, …). Keep
the keystore itself **outside the repository** - a published repo is one `git add -f` away from
leaking it, and a leak is not something rotation undoes: every install already out there keeps
trusting the old key until it is updated, so whoever has it can sign an APK those installs accept.
Without `keystore.properties` the build still succeeds and simply produces an unsigned APK, so a
clone needs no secrets; it fails loudly only if the file exists but names a keystore that does not.

The release config signs with **v2 + v3** and skips v1 (that is for Android < 7.0; `minSdk` here is
28) and v4 (only for `adb install --incremental`, and it emits a separate `.apk.idsig` sidecar). v3
is the one worth turning on deliberately - AGP leaves it off by default, and it is what carries a
proof-of-rotation lineage, so signing with it now is what makes it possible to ever move to a new
key without every install becoming an uninstall-first.

Generate one with:

```sh
keytool -genkeypair -keystore ~/.android/keystores/zui-tweaks-release.jks \
  -storetype PKCS12 -alias zui-tweaks -keyalg RSA -keysize 4096 \
  -validity 10000 -dname "CN=your-name"
```

**Back it up.** Android refuses an in-place update signed by a different key, so losing the keystore
means every future install has to be a clean uninstall first - which drops the app's settings and its
saved drawer folders. A debug build is signed automatically and is the easier path while developing.

### The LSPosed module (taskbar, icons, settings UI)

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Changing the `applicationId` makes a different app, so a build under an older one has to be
uninstalled first - otherwise it stays an enabled LSPosed module hooking the same processes.
Nothing here has been released, so on a fresh device there is nothing to remove.

Then in LSPosed: enable the module and add all three packages to its scope -
**ZUI 런처 / com.zui.launcher**, **com.zui.wifip2p** (virtual-touchpad cursor) and the IME
**com.google.android.inputmethod.latin**. The launcher is a system app, so turn on "show
system apps" in the scope list first.

A hook is only injected when a process starts, so whichever ones you scoped have to be
restarted. **The app does that itself** - change a setting and the pending card offers
*Restart now* per process. It takes a root shell to do it, which is not an extra
requirement: Magisk is already a prerequisite, `FORCE_STOP_PACKAGES` is
`signature|privileged` and out of reach, and `com.zui.wifip2p` runs as the system UID
where force-stop is ignored and only a signal ends the process. The equivalent adb lines
are still shown, and only shown, when root is refused.

If LSPosed's dialogs are blocked by "앱이 인터페이스를 가리고 있어…", ZUI's freeform sidebar is
the cause: it is an overlay window, and Android refuses to deliver a tap to a security-sensitive
dialog while one is on screen. Nothing can consent to that dialog for you, so the only thing to
automate is getting the overlay out of the way - the **Freeform sidebar** card near the top of
the settings screen does it over root. It sits above the Modules block rather than in it: it is a
one-off helper, not something installed.

**When to turn it off:** immediately before enabling this module in LSPosed, or before changing
its scope. **When to turn it back on:** as soon as that step is done - the freeform sidebar does
not work while it is off, so this is a two-minute detour, not a setting to leave flipped. While the
app has it off the card carries a warning tone, so it cannot be forgotten.

The card is always present rather than appearing only when the sidebar looks active, because that
state cannot predict when it is needed: the device this was written on sits at
`enable_zuifreeformbar=0` with the sidebar drawing no window, and it still blocked the dialog.
Hiding the card on that reading made it vanish for good after the first restore - the feature
became unreachable, and a restore that had worked perfectly looked like the card disappearing for
no reason. It reports three states instead: this app has it off, it is on screen now, or it is
quiet but can still come back.

It captures the two values before clearing them and restores what it captured, rather than
writing back a hardcoded `1` - a user who had the sidebar off already gets it left off. Revoking
the app op would not work: `com.zui.freeform.sidebar` runs as the system UID, where
`appops set SYSTEM_ALERT_WINDOW ignore` is ignored, so the `Settings.System` keys are what
actually stop it.

**Reporting the state is a different question from changing it, and the settings cannot answer it.**
ZUI writes `enable_temp_zuifreeformbar` back to `1` shortly after it is cleared: measured on the
device, one successful press left the two keys reading `0` and `1` while the window manager showed
the sidebar owning no window at all. Reading that key back therefore claimed "still on" about a
sidebar that was already gone - the card redrew as *Turn off*, the press looked like it had done
nothing, and the obvious response was to press it again. So the card asks the window manager
whether the package currently owns a window, and falls back to the *persistent* key for the case
where the sidebar is enabled but has not drawn yet. The transient key is still written and
restored, because that is part of making the overlay go away; it is just never believed.

From a PC the equivalent is:

```sh
adb shell su -c 'settings put system enable_zuifreeformbar 0'
adb shell su -c 'settings put system enable_temp_zuifreeformbar 0'
adb shell su -c 'am force-stop com.zui.freeform.sidebar'
# ... do the LSPosed step, then put both keys back to 1
```

Open the app once after installing. It writes its preferences file on first launch, and
until that file exists every hook reports the settings as absent and falls back to its
built-in defaults.

None of the above needs a terminal. The `.sh` files in this repo are development and
diagnostic tools - they measure and cross-check, and nothing in the setup depends on them.

Restarting the launcher can leave the **internal** display showing
`SecondaryDisplayLauncher` - wallpaper and status bar, no dock, no icons. That is a ZUI
quirk, not this module. Check and fix with:

```sh
adb shell dumpsys activity activities | grep topResumedActivity=
adb shell am start --display 0 -n com.zui.launcher/.drawer.DrawerLauncher
```

### The key-layout module (한/영 key)

Open **ZUI Tweaks**, find the *Keyboard fix (Magisk module)* card at the top, tap
**Install**, then **Reboot**. That is the whole procedure, and it needs no terminal: the
zip is carried in the APK, the app flashes it through `magisk --install-module`, and the
card reads the result back afterwards.

The module ships **no key layouts**. `customize.sh` derives them on the device it is
flashed onto: every layout on `/system`, `/vendor`, `/odm`, `/product` and `/system_ext`
that maps scancode 100 to `ALT_RIGHT` is copied with that one line changed, and nothing
else is touched. Deriving rather than shipping is not a convenience - a key layout is not
portable, so copying this tablet's files onto another device would replace every key on
its keyboard, not just right Alt. The earlier version of this module did exactly that and
was correct on one tablet, on one ROM build.

Two traps disappear with it. The files now come straight off the device, so they are
**LF by construction** - a CRLF `.kl` makes every keycode parse as `LANGUAGE_SWITCH<CR>`,
match nothing, and the whole layout falls back to stock without a word. And the
"exactly one line differs" invariant is asserted per file, by reversing the substitution
and checking the result is byte-identical to the original.

`customize.sh` can be exercised without a device: set `KL_ROOT` to a directory holding a
synthetic `system/usr/keylayout`, stub `ui_print`/`abort`/`set_perm_recursive`, and source
it. The alternative is finding out whether the sed expressions are right by flashing them.

**Re-installing over a mounted copy works, and is not the obvious thing.** Once the module
is mounted, `/system/usr/keylayout` is its own output - every layout reads
`LANGUAGE_SWITCH` and a naive scan finds nothing to patch. Magisk 30.7 keeps its mirror in
a separate mount namespace, so the pristine ROM is not readable from `customize.sh`.
Instead the scan applies two rules: patch whatever still says `ALT_RIGHT`, and carry
forward byte for byte whatever says `LANGUAGE_SWITCH` and is present in the installed
module. A layout patched by somebody else is left alone. That makes a re-install a no-op
on a working device and a repair on a ROM that has gained a new layout.

The card is the diagnostic too, and it distinguishes states nothing else does:

| card says | means |
|---|---|
| Active | no layout on the device maps scancode 100 to `ALT_RIGHT` any more |
| Installed but NOT mounted | Magisk did not load it at this boot. Reboot |
| Flashed and staged | Magisk moves it into place at the next boot |
| Mounted, some layout still on ALT_RIGHT | a ROM update added one. Re-install |

**"Installed but NOT mounted" is a real state, not a theoretical one.** On 2026-08-24 the
module sat there installed, enabled and byte-identical to the repo for a whole boot with
nothing mounted, and neither the Magisk app nor any check of `/data/adb/modules` could
tell it from a working install. Only two facts can: whether Magisk printed a load line for
it at this boot, and what `/system/usr/keylayout` actually contains. The card reads both.

The most common way in: **enabling a module in the Magisk app only writes a flag.** Nothing
mounts until the next boot, so a module toggled on after boot reads as enabled and does
nothing.

`sh scripts/verify-keylayout.sh` asks the same questions from a PC. It is a cross-check, not a
step - the scripts resolve the device through `scripts/device.sh` rather than a hardcoded address,
because wireless debugging picks a new port at every reboot and a script pointed at a
stale `host:port` prints empty output, which reads exactly like "all clear". That is how
the 2026-08-24 regression stayed invisible for nine hours. Override with
`ZT_DEVICE=<host:port>`.

On this tablet's ROM, 184 layouts live in `/system/usr/keylayout` and exactly four map
scancode 100 to `ALT_RIGHT` - so those four are what gets patched here:

| layout | covers |
|---|---|
| `Generic.kl` | any keyboard with no vendor layout of its own |
| `Vendor_17ef_Product_619e.kl` | Lenovo - the keyboard sold for this tablet |
| `Vendor_05ac_Product_0239.kl` | Apple |
| `Vendor_18d1_Product_5018.kl` | Google |

That table is what this ROM happens to contain, not a list the module carries. Another
device produces a different one and the card reports the count it found.

Everything else there is a gamepad or a remote that never defines scancode 100.

Two things that look wrong but are not:

- **No `Vendor_046d_Product_b378.kl`** for the Logitech MX KEYS S, even though it is the
  keyboard actually in use. The ROM has no such file, so the keyboard already resolves to
  `Generic.kl`, which is patched. Earlier versions synthesised one - it was a byte-copy of
  the patched `Generic.kl`, bought nothing, and forced Magisk to shadow the whole directory
  instead of bind-mounting single files, because the file was not in the ROM to begin with.
- `Generic.kl` is not keyboard-only. On this device the power key, the touchscreen and both
  headset jacks resolve to it too. None of them emits scancode 100, so nothing else changes
  behaviour - verified with `dumpsys input`, not assumed.

To try a change without a reboot, bind-mount a patched copy of the whole keylayout
directory - the SELinux labels matter, or system_server cannot read it:

It has to go across as a **script**, not as an inline `su -c`. Everything below the first
`&&` would otherwise be parsed by the device's own shell and run unprivileged, and the
`$(...)` would be expanded on the PC before adb ever saw it - see SESSION trap 6.15, where
that mistake returned a plausible wrong answer rather than an error.

```sh
cat > ./klo.sh <<'EOF'
#!/system/bin/sh
mkdir -p /data/local/tmp/klo
cp /system/usr/keylayout/*.kl /data/local/tmp/klo/
for f in $(grep -l "^key 100  *ALT_RIGHT" /data/local/tmp/klo/*.kl); do
  sed -i "s|^key 100   ALT_RIGHT|key 100   LANGUAGE_SWITCH|" "$f"
done
chcon u:object_r:system_file:s0 /data/local/tmp/klo /data/local/tmp/klo/*.kl
mount -o bind /data/local/tmp/klo /system/usr/keylayout
EOF
adb push ./klo.sh /data/local/tmp/klo.sh && adb shell su -c sh /data/local/tmp/klo.sh

adb shell su -c 'umount /system/usr/keylayout'      # revert, leaves nothing behind
```

The revert is a single command with no `&&`, so that one is safe inline.

Reconnect the keyboard afterwards - a layout is read when the input device is added, not
when the file changes. Confirm with `sh scripts/verify-keylayout.sh`.

## Diagnostic code is not in a release build

`StashHook` is kept because the reconnaissance behind it is worth keeping, and dropped from
release builds because the feature does not work: the guards flip and `isStashed()` never
becomes true, so the four stash keys are a switch that turns on and does nothing. Shipping
that is worse than shipping neither.

The mechanism is a build-type seam rather than a `BuildConfig.DEBUG` check, because
`buildTypes.release` has optimization disabled - there is no R8 pass to shake an
unreferenced class out afterwards, so a guarded call would leave the class in the APK
anyway. Instead there are two files with the same fully qualified name:

```
app/src/debug/java/io/laelaps/zuitweaks/xposed/Diagnostics.kt    calls StashHook
app/src/debug/java/io/laelaps/zuitweaks/xposed/StashHook.kt      only compiled for debug
app/src/release/java/io/laelaps/zuitweaks/xposed/Diagnostics.kt  no-op
```

`HookEntry` calls `Diagnostics.installStash(...)` and never names a diagnostic class, so
the release variant does not compile one. Verified by reading the dex rather than trusting
it - `io/laelaps/zuitweaks/xposed/StashHook` appears in the debug APK's dex and in neither
of the release APK's two:

```sh
./gradlew assembleRelease
unzip -p app/build/outputs/apk/release/app-release-unsigned.apk 'classes*.dex' | grep -ac StashHook
```

Add a no-op to both copies of `Diagnostics` to move any other hook the same way.

## Kill switch

```sh
adb shell "touch /sdcard/zuitweaks.off; am force-stop com.zui.launcher"
```
No hooks are installed while that file exists. (`/data/local/tmp` is unreadable to the
launcher under SELinux - use `/sdcard`.)

## Xposed layer — libXposed API 101

The Xposed side is a **modern libXposed (API 101)** module, migrated from the legacy
`de.robv.android.xposed` API (LSPosed now lists it as *API 101*, not *legacy*). Registration
is `META-INF/xposed/{module.prop` (`minApiVersion`/`targetApiVersion=101`, `staticScope=true`),
`java_init.list`, `scope.list}` — there is no `assets/xposed_init` and no legacy `<meta-data>`.
Scope is fixed by `staticScope` to `com.zui.launcher`, `com.zui.wifip2p`, and the IME
(`com.google.android.inputmethod.latin`).

The migration kept the hook bodies intact with a same-package **Java compat shim** — local
`XposedHelpers` / `XC_MethodHook` / `XposedBridge` / `XSharedPreferences` classes reimplemented
over libXposed 101 — so the ~15 hook files only lost their `de.robv` imports. The shim is
**Java, not Kotlin, on purpose**: Java members are platform types in Kotlin, which preserves the
null-leniency the original `de.robv` API had (a Kotlin shim forced strict nullability and broke
every call site).

Install = build then reboot: build (a debug build keeps `install -r` updating in place
seamlessly; a release build needs signing first, see *Build and install*), enable in LSPosed,
reboot. Scope needs no manual step.

## Settings

The app is the normal way to change anything. Settings travel over the **libXposed service
remote-preferences channel**: the app writes through `io.github.libxposed:service`
(`service.getRemotePreferences("settings")`, wired in `settings/RemotePrefs.kt`) and the hooked
processes read the same store via `XposedModule.getRemotePreferences("settings")`. Before the
API 101 migration this used a world-readable `SharedPreferences` + the `xposedsharedprefs`
manifest flag; that path throws `SecurityException` on modern Android, which is what produced the
"LSPosed is not passing these settings on" banner — the service channel is the fix.

Each setting names the process that owns it. A hook is only injected at process start, so
most changes are pending until that process restarts - the pending card offers **Restart
now** per process and does it over root. Only `collapsedPx` and `collapseDelayMs` re-apply
live, via **Reload**.

Both Magisk modules are installed from a single **Modules** block at the top of the screen. They
used to sit in the sections their settings belong to - Keyboard and Misc - which left two
prerequisites at opposite ends of a long scroll, and the keyboard card additionally *moved* once
its module went ACTIVE. Setting the device up is one job, so it is one block, and a card that
stays put beats a card that tidies itself away.

Only one privileged action runs at a time, and the screen names **which control started it**
(`ActionOwner`). A single screen-wide `busy` flag put every module card into its progress state
and printed the same shell transcript under all of them, so pressing Install on one module looked
like it had installed both; the same flag could not say which of several **Restart now** buttons
was the one running. The spinner and the shell output now belong to the control that asked for
them, and everything else is merely disabled.

Diagnostic keys are deliberately not in the app. They stay in the debug overrides below.
The line between the two is worth stating: **if an honest one-line summary has to tell the
reader which value to pick, it is not a setting.** `altReplayDelayMs` defaulted to `0` and
its summary said "leave at zero", so it is override-only. `altDeferTimeoutMs` passes the
test - 250 and 400 are both defensible and the trade-off is real in both directions - so it
stays.

### Settings are the app's, and a file cannot take them over

Anything with a toggle lives in the module's own preferences and nowhere else. The debug
channels below may only introduce keys the schema does **not** declare; a setting that has
a toggle is read from the app, whatever a file says.

That rule is not tidiness, it is a fault this project shipped. The drawer-folder feature
was reachable only from `/sdcard/zuitweaks.conf`, and the hooks read it at process start.
The launcher starts at boot **before external storage is readable by it**, so the file
layer came back empty there while the same file read fine in the touchpad process moments
later - measured 2026-09-04:

```
config sources in com.zui.launcher: prefs=readable(0 settings), file=unreadable,       settings=no-context
config sources in com.zui.wifip2p:  prefs=readable(0 settings), file=readable(4 keys), settings=no-context
```

Every key fell to its hardcoded default, the drawer-folder hooks never installed, and
nothing in the app could turn them back on because they had no toggle. Restarting the
launcher fixed it, which is the whole diagnosis: the feature was fine, the channel was not.

libXposed API 101's remote preferences reach **every** scoped process - launcher, touchpad
and the IME all report `prefs=readable` above - so the reason the file channel existed in
the first place (the old bridge not reaching an unprivileged process, trap 6.9) is gone.

To move settings as a file, the settings screen has **Export** and **Import**. They use the
system document picker, need no storage permission, and run only when asked.

**Drawer folders travel in the same file.** They cannot simply be read: grouping lives in a SQLite
database inside `com.zui.launcher`'s own data directory (`FolderStore`), and SELinux keeps this app
out of another app's data. So Export asks the launcher for them - an **ordered** broadcast, because
that is the only kind whose receiver can answer, through `setResultData` - and `Control` replies with
`folder.*` lines that are appended to the settings text (`settings/FolderBridge.kt` on the app side,
`FolderStore.exportText/importText` on the launcher side). Import hands the whole file back the same
way; the launcher picks out the lines it owns, and `SettingsStore` ignores them because they are not
in the schema, so one file carries both without either end parsing the other's part.

If nothing answers within four seconds the export says so instead of writing a file that quietly
claims the device has no folders. That is a real state, not an error: it means the module is not
active in LSPosed. Import **replaces** the folders rather than merging - a restore is "make this
device look like that one", and leaving folders the file does not mention is not that.

The suggested filename is `zuitweaks-settings.conf`, and the MIME type asked of the picker is
`application/octet-stream` rather than `text/plain` **on purpose**. AOSP's `FileUtils.splitFileName`
appends the extension belonging to the MIME type whenever it disagrees with the name, so `text/plain`
saved the file as `zuitweaks-settings.conf.txt`; an unknown extension like `.conf` maps to
octet-stream, which makes the two agree and leaves the name alone.

## Debug overrides

Both channels below carry **diagnostics only**. A key the app declares is ignored here, so
these cannot switch a feature off behind the app's back; what they can do is turn on a probe
or a stash guard without a rebuild.

`/sdcard/zuitweaks.conf`, `key=value` per line, re-read on process restart - no rebuild.

Only apps with storage access can read that file, and not reliably at boot. The same keys
can also be set in `Settings.Global`, which any process can read without permission:

```sh
adb shell settings put global zuitweaks_conf "altFix=1;imeProbe=1"
adb shell settings delete global zuitweaks_conf
```

| key | default | meaning |
|---|---|---|
| `autohide` | `1` | collapse/expand at all |
| `collapsedPx` | `2` | window height when hidden. The app offers 1..12, which is where it was measured to stop being clean - 13 puts one row of the taskbar on screen (RECON N-4) |
| `collapseDelayMs` | `1200` | how long after the cursor leaves before collapsing |
| `stripInsets` | `1` | zero the taskbar's insets so apps use the whole screen |
| `transient` | `0` | switch to AOSP's floating-pill taskbar (changes appearance) |
| `inApp`, `visualStash`, `manualStash`, `forceHide` | `0` | force Launcher3 stash guards open. **Debug builds only** - no effect in a release build, where `StashHook` is not compiled in |
| `zuiDebug` | `0` | enable ZUI's own `FLAG_IN_APPPP` logging |
| `probe` | `0` | verbose window/insets observation logging |
| `probeInput` | `0` | log every pointer event that reaches the taskbar window |
| `cursorHotZonePx` | `8` | how close to the bottom the virtual cursor must get to reveal the bar |
| `cursorKeepOpenPx` | `200` | the virtual cursor may rise this far before the bar is allowed to close |
| `tapToReveal` | `1` | expand when the collapsed strip is tapped |
| `iconFix` | `1` | pre-scale app icons for the external taskbar (see below) |
| `iconHeadroom` | `115` | pre-scale target as a percent of `taskbarIconSize`; 115 lands on the actual draw size for a 1:1 blit |
| `iconProbe` | `0` | log source/destination sizes for every icon draw |
| `control` | `1` | register the adb broadcast receiver |
| `controlReflect` | `0` | let that receiver invoke a launcher method by name (`ctl.sh <method>` and `stash:<method>`). Off by default: the receiver is exported, so the string comes from whoever sent the broadcast |
| `altFix` | `0` | right-Alt language-switch key fix (see below) |
| `altDefer` | `1` | queue keys until the switch lands; `0` only strips the modifier |
| `altDeferTimeoutMs` | `400` | release queued keys anyway if no switch arrives |
| `altReplayDelayMs` | `0` | extra wait after the switch before replaying. Debug override only - not a setting in the app |
| `imeProbe` | `0` | trace every key, subtype change and IME output |

## The 한/영 key eating a character

With a physical keyboard, switching language and typing immediately went wrong two ways:
`이` came out as `ㅣ`, and `한/영` then `This` produced `쏘ㅑㄴ` - the switch appearing not
to happen at all. Waiting ~500ms avoided both. Neither was an IME or Hangul-composition
problem. Traced with `imeProbe=1`:

This is **not specific to one keyboard**. It applies to every wired or wireless keyboard
connected to this tablet: none of them has a dedicated 한/영 key, so right Alt is it, and
`Generic.kl` maps scancode 100 to `ALT_RIGHT` for any keyboard with no vendor-specific
layout of its own. Everything follows from the key being a modifier:

1. A letter pressed before right-Alt is fully released reaches the IME as Alt+letter and
   is discarded: `onKeyDown keyCode=32 meta=0x22 consumed=true`, no output.
   (`0x22` = `META_ALT_ON | META_ALT_RIGHT_ON`.)
2. The switch chords on this device are **Alt+Space and Alt+Shift**. Typing a capital
   letter means pressing Shift - and with Alt still held that is Alt+Shift, which switches
   *again*, undoing the switch just made. Two `SUBTYPE CHANGED` lines land in the same
   millisecond, `locale=` then `locale=ko-KR`.

### The fix: stop it being a modifier

Remap scancode 100 to `LANGUAGE_SWITCH` in the key layout and both faults go with it: the
key stops carrying `META_ALT_*`, so nothing is discarded, and it stops forming an Alt+Shift
chord, so the second switch never happens. That is what the Magisk module above does. No
hook can achieve the same thing, because the second switch is decided in system_server
before the IME is handed anything.

> This paragraph was rewritten on 2026-08-24 rather than restored - the original was lost
> when the section above it was replaced.

### The Xposed fallback (`altFix`, off by default)

`AltGraveKeyFix` queues key events while right-Alt is held and replays them with the ALT
bits cleared once the subtype change lands. It fixes fault 1 only - fault 2 is decided in
system_server before the IME is given the event, so no hook in the IME process can undo
it. Kept for a keyboard whose layout cannot be remapped. It must leave chord keys
(`SPACE`, `TAB`, the modifiers) completely untouched: consuming the SPACE of Alt+Space
stops the language switch working at all.

## Diagnostics

```sh
sh scripts/ctl.sh dump                      # taskbar + stash controller state
sh scripts/ctl.sh collapse | sh scripts/ctl.sh expand
sh scripts/ctl.sh <anyPublicMethod>         # on TaskbarActivityContext
sh scripts/ctl.sh stash:<anyPublicMethod>   # on TaskbarStashController
sh scripts/verify.sh                        # window geometry and applied insets, both displays
adb logcat ZuiTweaks:V '*:S'
```

The receiver is exported - it has to be, the settings app and `com.zui.wifip2p` are
separate packages - so it answers only a fixed list of commands: `dump`, `viewdump`,
`collapse`, `expand`, `config`, `edgeEnter`, `edgeExit`. The two lines above that invoke an
arbitrary method by name are reflection into the launcher driven by a string any app on the
device could send, so they are refused unless `controlReflect=1` is set in one of the debug
override channels below.

Screenshot the external display with its **physical** id - `screencap` rejects the
logical one:
```sh
adb shell dumpsys SurfaceFlinger --display-id     # find it; the Philips panel is 4629995176798887445
adb shell screencap -d 4629995176798887445 -p /sdcard/e.png
```

> The external display's **logical** id is not stable - it was 13, and became 2 after a
> reboot. Nothing in the module hardcodes it: the external taskbar is identified by its
> context class (`TaskbarActivityContextDp`) and, where only a display is available, by
> `displayId != 0`. Keep it that way.

## Sharper icons on the external taskbar

Launcher3 rasterises app icons once per process, at a size derived from the *internal*
display. Measured here: a 198x198 bitmap, which the internal taskbar draws at 198x198 -
pixel perfect - and the external taskbar draws into a 69x69 rect. Anti-aliasing and
bitmap filtering are already enabled and the external display's density plumbing is
correct; what is missing is mipmaps, so that 2.87x minification is a single bilinear tap.
Thin strokes drop out and diagonals stair-step - the icons look like they were drawn in
MS Paint.

`IconQualityHook` pre-scales the bitmap by repeated halving (a box filter, which is what
a mipmap chain would do) before `FastBitmapDrawable` ever sees it, gated on the icon
being built against `TaskbarActivityContextDp` so the internal display keeps its
pixel-perfect path. It scales to 115% of `taskbarIconSize` rather than exactly to it: the
drawable's bounds at draw time are larger than that field - measured 69 against a
taskbarIconSize of 60 - so scaling to the field alone would end in an upscale and soften
the result. 115% lands on 69, making the draw a 1:1 blit with no resampling at all, which
compared sharpest against 60px and 90px targets.

The icons still carry less detail than the ones in the app drawer, and always will: the
drawer draws a 198px raster on a 440dpi display, the external taskbar draws 69px on a
213dpi one - under an eighth of the pixel area. What this removes is the resampling
artefacts, not the size difference.

## Fixed: apps render a phone layout on the external monitor

On the external monitor many apps (Laftel, Firefox, Settings, …) draw their **phone** layout -
few columns, oversized cards - while the tablet's own screen shows the tablet layout. It is not
one app; it is systemwide.

Window size is not the cause. Apps decide "tablet vs phone" the react-native-device-info way:
`min(realPixels) / DisplayMetrics.density >= 600`, read from `getDefaultDisplay().getRealMetrics()` -
the display's raw pixels and density, never the window's `Configuration`. So resizing or
maximising a window never changes the verdict (an early launcher hook that forced these
launches fullscreen was tried and removed for exactly this reason).

The real bug is the density the *app/default context* reports for the external display. Measured
with a probe replicating that exact call:

```
external monitor, Application context:
  getRealMetrics() = 2560x1440 px @ density 2.75 (440 dpi)  ->  1440 / 2.75 = 523.6  < 600  -> HANDSET
internal panel, Application context:
  getRealMetrics() = 3040x1904 px @ density 2.75 (440 dpi)  ->  1904 / 2.75 = 692    >= 600 -> TABLET
```

The external monitor's own density is 213 dpi (1.33) - the display-adjusted *activity* context
sees 1.33, and so does the whole system (`wm density -d 2` = 213, the display's `StaticDisplayInfo`,
the per-activity `Configuration` = sw1082dp). The pipeline is **not** broken. The single wrong
value is the legacy `getDefaultDisplay().getRealMetrics()` on the app/default (display-0) context:
ZUI hands it the monitor's pixel size (2560x1440) with the internal panel's 440 dpi. Because that
is the display's raw metrics, it is 2560x1440@440 regardless of window size - which is why the
verdict is HANDSET at any window size, and why native apps that read the sw600dp `Configuration`
instead (Settings renders two-pane on the monitor) are unaffected.

**Fixed - a standalone Zygisk module, controlled by this app (2026-08-27).** The correction is:
on `Display.getRealMetrics`/`getMetrics`, when the returned metrics carry the monitor's size but a
foreign (internal) density, rewrite the density to the monitor's own value (read once from the
external `Display`, nothing hardcoded); a no-op on every other surface. What changed is *where it
runs*. It is **not** an LSPosed hook any more - it lives in a separate Magisk **Zygisk** module,
`zygisk_extdensity` (source in `zygisk-extdensity/`; the compiled `arm64-v8a.so` is snapshotted
into `magisk/zygisk_extdensity/`), for two reasons: LSPosed injects
only into scoped apps (no "all apps"), and the fix needs a real Java-ART hook in the app process,
which LSPosed's own LSPlant would double-hook if the module also did it.

- **Global module, app-picked scope.** Zygisk loads the module into every app, but it acts only on
  packages the user selects on this app's **App Selection** screen (Misc section). Selection is a
  per-package boolean property `persist.zui.extdensity.<fnv1a(pkg)>=1`, which the app sets via root
  (`resetprop`) and the module reads live in `postAppSpecialize`. Changing the selection applies on
  that app's next start - **no reboot** (the module is already resident). No LSPosed involved.
- **How it hooks.** LSPlant (ART Java hook) built from source and statically linked with **Dobby**
  (the inline-hook backend - thread-safe, which matters) and **xDL**, into one self-contained `.so`
  (no shipped libc++ / companion). A small embedded dex carries the Java callback that runs the
  density correction.
- **Safety.** Only hooks **after `sys.boot_completed`**, so a hook problem can never break the boot
  (boot-time processes - SystemUI, launcher - are never touched); recovery for the module itself is
  just disabling it in Magisk. `system_server` is never touched.
- **Installed from the app**, like the keyboard module. Its zip is snapshotted into
  `magisk/zygisk_extdensity/` and packed into the APK assets (`packExtDensityModule`); the **Misc**
  section's module card (`settings/ZygiskModule.kt`) installs it via `magisk --install-module` and
  reports NOT_INSTALLED / PENDING_REBOOT / DISABLED / ACTIVE. One reboot to first activate; app
  selection after that is reboot-free. Re-snapshot the `.so` whenever the module is rebuilt -
  `zygisk-extdensity/README.md` has the build steps, the third-party clones it needs, and how to
  tell from `logcat -s ExtDensityZ:V` whether a build actually loaded.

Dead ends kept for the record: the LSPosed `ExternalMetricsFix` (removed - superseded by the Zygisk
module, and it double-hooked); the launcher-fullscreen hook (window size was never the gate); the
system_server `getDisplayInfoLocked` hook `ExternalDisplayDensityFix` (removed - ART-inlined, and
aimed at a pipeline that was already correct); a Zygisk **companion** socket for the allowlist
(`connectCompanion` failed for every app on this Zygisk - replaced by the property channel above);
and an all-apps mode with the **And64InlineHook** backend (thread-unsafe - it crashed the
multi-threaded external launcher; fixed by moving to Dobby). The `base_density_for_external_displays`
aconfig flag is a red herring: the display already reports its own 213 - measured on-device with a
probe app that dumped `DisplayMetrics` from both an activity context and the application context on
each display.

## Not fixed: the bar's own artwork is stretched

The app icons were fixable because they are bitmaps we can pre-scale. The rest of the bar
is soft for the opposite reason - it is **up**scaled. From a live view dump:

```
NearestTouchFrame 2560x80
  LinearLayout 207x80
    ImageView 69x69  drawable=VectorDrawable  intrinsic=32x32   <- back / home / recents
DoubleShadowBubbleTextView 60x60                                <- app icon view
```

The nav-button vectors are authored at 24dp and drawn into ~52dp buttons, a 2.16x
upscale, and at 6x magnification their edges bleed over several pixels. No ancestor view
carries a scale and the density is correct (213), so this is ZUI shipping assets that do
not match their own layout, not something the module introduced.

It could be forced sharp by rasterising each `VectorDrawable` at the view's size and
swapping in a `BitmapDrawable`, but the nav buttons are re-tinted at runtime against the
background brightness (`TaskbarActivityContext.onNavButtonsDarkIntensityChanged(float)`), and a
baked bitmap would likely break that. Sharpness traded for colour adaptation - left
alone deliberately.

## Rejected: display-wide cursor monitor

The alternative to depending on the taskbar window still being touchable while collapsed is
to start a `com.android.systemui.shared.system.InputMonitorCompat` on the external display
and read hover events from it. AOSP does exactly this - it is how a transient taskbar
normally reveals itself - but the per-display branch in `TouchInteractionService` is gated
on `Flags.enableGestureNavOnConnectedDisplays()`, a compile-time `false` in this build, so
the only monitor it creates covers display 0.

It would be buildable: the launcher already declares `android.permission.MONITOR_INPUT`, so
no extra privilege is needed, and a monitor that never calls `pilferPointers()` observes
events without consuming them.

**It was not needed and the code is gone.** A `CursorMonitor` class existed for a while
with a caller in `AutoHideController` that was never itself called - unreachable, not
disabled, though earlier versions of this file said otherwise. The window-level hover hook
does the job, so it was deleted on 2026-08-25 rather than kept as scaffolding for a feature
nobody was waiting for. This section is the part worth keeping: if the hover path ever
stops working, this is the approach to reach for, and this is why AOSP's own one does not
cover the external display.

## The two cursors

A real USB/Bluetooth mouse is a pointer: the taskbar window receives `ACTION_HOVER_*` and
`AutoHideController` reacts directly.

ZUI's "tablet as a virtual touchpad" cursor is not a pointer at all. Moving it injects
nothing whatsoever - the app just repositions an `ImageView` in a
`TYPE_SECURE_SYSTEM_OVERLAY` window it owns, and only taps are injected, as
touchscreen-source events. Android never synthesises hover for a touchscreen source, so
no hook in the launcher can see that cursor. `VirtualCursorHook` therefore runs inside
`com.zui.wifip2p` and reads the coordinates from
`com.zui.wifip2p.touchpad.TouchPad.updateViewPos(float, float)`, forwarding only hot-zone
transitions to the launcher as broadcasts.

That path needs `com.zui.wifip2p` in the module's LSPosed scope, and the process is
system-UID and survives `am force-stop` - restart it with
`adb shell su -c "kill -9 $(adb shell pidof com.zui.wifip2p)"`.

## Virtual touchpad in portrait

The touchpad refuses portrait for one reason only: `TouchPadActivity` carries
`android:screenOrientation="sensorLandscape"` in the manifest. There is no runtime orientation code
to fight - a full dex grep finds no `setRequestedOrientation`, no `SCREEN_ORIENTATION_*`, no rotation
reads in the touchpad package - and the cursor math is orientation-independent: `DispatchEvent`
accumulates scaled `getX()/getY()` **deltas** (trackpad-style) and clamps them to the **external**
display's bounds, so a physical swipe maps the same whichever way the tablet is held. So the whole
fix is `TouchpadOrientationHook`, in the same `com.zui.wifip2p` process: hook
`TouchPadActivity.onCreate`/`onResume` and set `setRequestedOrientation(SCREEN_ORIENTATION_FULL_SENSOR)`
(the natural extension of the existing `sensorLandscape`; tunable via `touchpadOrientation`). No
coordinate patch is needed. The activity has no `configChanges=orientation`, so the first rotation
recreates it, which is safe (its `onResume → initDispatchEvent` rebuilds the cursor). Gated by the
**Misc → Virtual touchpad portrait** toggle (`touchpadPortrait`, default on).

## Drawer app-list folders (grouping)

ZUI's drawer (all-apps) has no folders (only the workspace does). This adds them via LSPosed
(scope `com.zui.launcher`), built against **ZuiLauncher 18.2.0.0400**. Enable with
the **Launcher** section of the app (*Drawer grouping* + *Drawer folders*), on by default.

- **Create**: long-press a drawer app and drop it onto another → a folder (persisted). Drop an app
  onto a folder icon → the app joins that folder. Folder-onto-folder does nothing.
- **Storage**: `FolderStore` — framework SQLite at
  `com.zui.launcher/databases/zuitweaks_folders.db` (`groups`, `members` keyed by
  `ComponentName.flattenToShortString`). Survives close and reboot. It is also what the settings
  file carries as `folder.*` lines — see *Settings* for why that needs a broadcast round-trip.
- **Render** (`DrawerFolderRender`): hook `AlphabeticalAppsList.updateAdapterItems`, swap the
  ordered `AppInfo` list (field `d`, R8-fragile) to drop members and insert a synthetic folder
  `AppInfo` (sentinel component `io.laelaps.zuitweaks.folder/g<id>`, composite preview icon). The
  launcher renders it as a normal grid icon (`AdapterItem.asApp`, no new view type). Tap is
  diverted via `Launcher.startActivitySafely` → the overlay.
- **Overlay** (`DrawerFolderOverlay`): reuses the real `Folder` view (`FolderIcon.
  inflateFolderAndIcon` + `animateOpen`), DB-guarded so the favorites DB is untouched; backdrop blur
  re-applied via `ZuiFolderBgImageView.setRenderEffect(createBlurEffect(70,DECAL))`. Rename persists
  via a `FolderInfo.setTitle` hook. Dragging an item out of the folder removes it.
  - **Centred, single-motion open** (matches a real workspace folder): the anchor `FolderIcon` is
    sized small and centred (default LayoutParams made it full-screen, corrupting every icon-relative
    computation), the positioner `Folder.j0()` is hooked to set the folder's `lp.x/y`+pivot to screen
    centre, and — crucially — `Workspace.getNewScaleFolder()` is forced to `1.0` for the duration of
    our `animateOpen`. Over the all-apps drawer that method returns the zoomed-out workspace scale
    (~0.9), which the native animator uses as the open target, so the folder grew to ~90% then snapped
    to full at animation end — a visible 2-stage open. Forcing 1.0 lets the native clip-path reveal be
    the sole, smooth motion.
- **Gestures** (`DrawerLongPressHook`): the drawer is kept open during an in-drawer drag by
  suppressing `StateManager.goToState(SpringLoaded/Normal)` while the pointer stays in the app
  grid; leaving the grid hands off to native home placement. Edge auto-scroll and a folder-item
  drag model (reorder / menu-on-no-move-release / drag-out) are layered on the same hooks.
  - **Overlay → drawer → home in one gesture**: dragging an app out of the folder overlay hands the
    (still-live) drag to the drawer (grid found via `launcher.getDragLayer()`, since the drag view is
    a detached copy) and engages the drawer's live reorder (`beginDragOutReorder`), so the grid reflows
    a gap and you can **drop it into the drawer at a chosen slot** while still holding; carrying on past
    the drawer grid triggers `handOff`, which reveals the workspace (`goToState(SpringLoaded)`) so the
    app drops on the home screen instead — no release-and-re-long-press either way.
  - **User-set order (persisted)**: reorder apps inside the folder by drag; the order is saved to
    `FolderStore.members.ord` and the folder opens in it. Making the reused folder's native reorder
    behave needed: distinct item ranks (0,1,2… set at build), NOT suppressing `Folder.onDragOver`
    (so a gap opens between items), clamping `FolderPagedView.findNearestArea` to `itemCount-1` (the
    folder over-allocates a grid row, leaving a phantom empty cell the target oscillated to), reseeding
    `Folder.startDrag`'s `mEmptyCellRank` from the dragged view's live cell (its `ItemInfo.rank` is
    stale under our DB guard), and reading the new order from the item VIEWS' on-screen positions
    (not the stale ranks).

- **Icon sizing** (`DrawerFolderRender.installIconScaleFix`): the folder is a plain `BubbleTextView`,
  so tapping it starts a press/scale animation on its `FastBitmapDrawable`; because we hijack the tap
  to open the overlay, that animation is never reversed and the scale would stick below 1.0 (the
  folder shrank after a tap). Fixed by pinning our folder drawable's scale to `1.0` before each draw.
  The composite icon itself is bbox-matched to a real member's opaque bounds so its content fraction
  equals apps'. (Diagnostics: `foldericonprobe=1`.)

### On the external monitor

The folders work on the external display too, and two facts shape how. First, the folder **overlay** is
hosted by a non-`Launcher` context — the external all-apps is `SecondaryDisplayLauncher`, the taskbar
all-apps is `TaskbarActivityContext` / `TaskbarOverlayContext` / `ZuiNavOverlayContext` — so code that
assumed a real `Launcher` (`Launcher.getWorkspace()`, `Launcher.startActivitySafely`) missed or crashed
and is handled specially below. Second, for **drags**: the surface the user actually reorders and groups
on is the external **taskbar** all-apps, and its drags flow through the *same internal
`LauncherDragController` path* as the tablet's own drawer (`drawer drag: ACTIVE … grid=[…2566…]`, the
external width). So the reorder, drag-to-group, drag-out and auto-open behaviours from *Drawer custom
ordering* (below) **are** the external behaviour — verified on the injectable internal display, they
cover the external. (`SecondaryDragController`, used by the separate `SecondaryDisplayLauncher` *home*
all-apps, is wired with isolated drag state as a safety net for that surface.)

Overlay handling for the non-`Launcher` contexts:

- **Tap** opens the overlay through `ItemClickHandler.INSTANCE.onClick` plus a wrapper around each
  context's `getItemOnClickListener`. The internal hook point — `Launcher.startActivitySafely` — is
  never reached on the external (those contexts don't override it, and its `ActivityContext`
  interface-default form can't be hooked because ART dispatches it through a per-class copied method).
  Without this, a folder tap launched the sentinel component and the system showed "not installed".
- **Close** is forced non-animated on a non-`Launcher` context: the native close animator
  dereferences `Launcher.getWorkspace()`, which is null there and restarted the launcher.
- **Opening** swallows the same null-`Launcher` exception from `animateOpen`, so the temporary anchor
  icon is still cleaned up — otherwise it lingered as a nameless "ghost" folder in the screen centre.
- **Editing a folder that is open on the other display** no longer crashes. Opening a second overlay
  used to overwrite the single `currentFolder` and orphan the first, whose later (animated) close
  then dereferenced the null external `Launcher.getWorkspace()`. Now `open()` closes any still-open
  overlay first, and the non-animated-close guard covers *any* folder on a non-`Launcher` context,
  not just the current one.
- **Long-pressing an app inside a folder** shows ZUI's own uninstall popup (`showForIconDp`), driven
  by a real-long-press timer on touch (the external touch long-press otherwise only flashes the press
  feedback, and the timer is cancelled if the folder closes) and by intercepting
  `TaskbarDragController.startDragOnLongClick` on mouse.

Drag behaviours (shared internal path; each fixed on the injectable internal display):

- **Drag-to-group actually persists.** The join is hit-tested at the folder's current on-screen
  position at release (so a non-adjacent folder works — see *Drawer custom ordering*), but it used to
  log success and add nothing: the source was read from the dragged view's live `.tag` at drop, and
  that view is a RecyclerView item recycled during the reflow, so by release its tag had drifted to
  another item (a folder sentinel) and the drop was read as a no-op folder-onto-folder. Fixed by
  capturing the dragged app's `AppInfo` at drag **start**.
- **Dragging an app out of a folder into the drawer has live placement.** The still-held drag hands to
  the drawer and engages the same live reorder as a normal drawer drag, so the grid reflows a gap under
  the pointer and a release drops the app at the chosen slot (before, it floated with no reflow and you
  had to release and long-press again); dragging on past the drawer still drops it on the home screen.
- **Dropping that dragged-out app onto another folder joins it *and* auto-opens the folder.** Rebuilding
  the reused `Folder` called `makeWorkspaceItem` on every member, but an item dragged straight out of a
  folder is already a `WorkspaceItemInfo` (no such method), so the open threw and the target folder
  stayed shut. The overlay now accepts a `WorkspaceItemInfo` member as-is, and the open reads the
  freshly-rebuilt member list so the just-added app is already a proper `AppInfo`.
- **Press-scale flicker while dragging** (a hovered icon repeatedly shrinking/popping) is suppressed:
  every drawer icon's press-scale is pinned to `1.0` for the duration of a reorder or external drag,
  and at the end of the reorder every visible icon's scale is reset so a hovered neighbour isn't left
  shrunk after the drop.

The overlay handling, the drag-to-group persistence, and the drag-out placement/auto-open are all
verified in code and on the injectable internal display (which shares the external taskbar's drag path).
Two things still await a real mouse/touchpad, because an external / `SecondaryDisplayLauncher` drag and a
mouse long-press cannot be produced with `adb input`: the in-folder long-press menu on **mouse** (the
touch path is verified; the mouse path is the same code), and the press-scale flicker being visually gone
on a real external drag (the pin and the end-of-reorder reset both fire, but the accept-scale they mask
can't be triggered under injection).

## Folder membership, and the picker that fills it

An app belongs to **exactly one** folder. `members.component` is UNIQUE, and adding an app *moves*
it: it leaves whatever folder it was in, and a folder left holding one app is dissolved - the rule
`cleanup()` already applied at startup, applied at the moment it becomes true. Without that
constraint the same app could sit in two folders, and since the drawer maps each app to a single
folder, it would show in one and be stored in the other: added to a folder, back in its old one on
the next render.

The folder's own **앱 추가** picker is mirrored into the store, and finding where to do that was the
whole difficulty. `FolderInfo.add(ItemInfo)` is the obvious hook and the wrong one - it has exactly
one caller in the entire launcher, the startup loader, so it fires while the launcher restores its
own folders and never from the UI. A hook there logged four calls at every launcher start and not
one when a user added an app. The picker is `BigFolderIconSelectDialog`, and it calls
`Folder.addFolderContent(item, rank, animate)`, appending straight into `mInfo.getContents()`;
unchecking a member calls `Folder.removeFolderContent`. Both are public, keep their names across
18.1.0 and 18.2.0, and every overload funnels into the widest one. Both are mirrored, because the
picker adds *and* removes - mirroring only the add produces the same inconsistency in the other
direction.

`addFolderContent` also calls `ModelWriter.addOrMoveItemInDatabase(item, mInfo.id, …)`. This
module's `FolderInfo` is transient, `id = -1`, with no row of its own, so each add through the
picker was inserting a **real favorites row with `container = -1`** - an orphan belonging to no
folder, which the next launcher start loaded back and handed to `FolderInfo.add`. Those startup
calls were not the loader being tidy; they were this module's litter returning. The launcher's model
writes are suppressed while one of our own `addFolderContent` calls is on the stack.

## Folder open and close animate on the folder's icon

The close animator builds its target rect live, at close time, from `mFolderIcon`'s left and top -
nothing is captured at open. The synthetic anchor icon this module creates used to sit at the screen
centre, so folders collapsed into the middle of the screen; removing the anchor after the open does
not undo that, because `removeView` leaves `mLeft/mTop` intact and a detached view still reports
where it was last laid out. The anchor is placed on the real drawer icon instead, and `layout()` is
called explicitly as well as setting the layout params, because the anchor is removed before the
drag layer's next layout pass. Open comes along for free: the two directions share one rect and only
swap from/to.

## The blurred backdrop behind an open folder

The launcher screenshots itself into `ZuiFolderBgImageView` and then pads that view down to exactly
the rect it captured, so even a partial capture is drawn 1:1 in the right place. 18.2.0 expands the
crop to *at least* the whole drag layer (plus a 100px margin, hence its negative padding); 18.1.0
only unions the folder icon's rect with the folder body and does not expand, so its capture is a
small box anchored on the icon. Widening `Folder.N` to the drag layer before the capture runs makes
the union full-screen on either build, and the padding then comes out zero on its own.

Two things that look like fixes and are not. **Do not zero that padding**: it puts a partial bitmap
in a full-screen content box on a view the launcher never assigns a scale type, and `FIT_CENTER`
magnifies it - the "launcher looks zoomed in behind the folder" report - while on 18.2.0 it shrinks
the image instead by discarding a deliberate negative padding. **Do not stretch it** with `FIT_XY`
either, for the same reason in a different disguise. Widening the crop is the only lever; the
`blur: backdrop …` log line reports the padding so it is clear whether it worked.

## Drawer custom ordering (drag-to-reorder)

The drawer is normally `AlphabeticalAppsList` (fixed A-Z). This lets the user set a custom order.
Enable with *Custom drawer order* (plus *Drawer grouping*) in the app. Both are on by default.

- **Order model** (`DrawerFolderRender.imposeDrawerOrder`): a persisted slot list (`FolderStore` meta
  `drawer_order`; slots are app components + folder sentinels). We already replace the ordered list in
  `updateAdapterItems`, so we just sort it by this order — no need to disable the launcher's sort.
  Until the user first reorders it mirrors the fullest list's natural order (so the drawer looks
  normal); after that it is frozen and **new apps append to the end**, new folders insert at their
  first member's slot (so both stay reorderable — the "one folder won't move" fix).
- **Live reorder** (`DrawerLongPressHook` + `DrawerFolderRender.beginReorder/updateReorder/commitReorder`):
  long-press an app and drag; `findChildViewUnder` picks the app under the pointer, and the dragged
  slot is moved before/after it in an in-flight order that the grid re-renders (`onAppsUpdated`). The
  dragged item's grid view is hidden (`installReorderHideHook`) so only the floating drag view shows.
  The **release** point decides group-vs-reorder: releasing an app **on a folder** (anywhere on the
  icon, adjacent or not) adds it to that folder; releasing on an **app's centre** makes a new app+app
  folder; releasing anywhere else commits the reorder. During the drag the app reflows past everything
  (folders included), so it can travel to a folder's far side — you move an app **past** a folder by
  releasing on an app slot beyond it, and **into** a folder by releasing on the folder. The join is
  hit-tested at the folder's current position, so a non-adjacent folder works regardless of the
  reflow. (A folder drag never groups — folder-onto-folder is a no-op — so it always reorders.)
  Dragging out goes home; a no-move release shows the app menu. NOTE: the reflow **snaps** (no per-item
  slide) — the launcher's
  all-apps RecyclerView has a null ItemAnimator and its diff turns a move into remove+insert, so
  `onAppsUpdated` can't animate a clean move; adding an animator only flickers. Accepted by design.
- **A-Z index hidden** (`installHideFastScroll`): the fast scroller's `draw` is skipped (meaningless
  under a custom order).
- **Tunable deltas** (settings UI → **Launcher** section, `Schema`/`Labels`): `drawerDragSlop` (40px),
  `folderDragSlop` (30px), `groupZonePct` (36%, the folder-vs-insert centre band). Defaults match the
  hardcoded values; read at drag time, so restart the launcher to apply. EN + KO localized.

## License

Apache License 2.0 — see `LICENSE`.
