"""Compile hooker/Hooker.java to a dex and emit it as zygmod/src/main/cpp/hooker_dex.h.

The module carries its Java callback as a byte array compiled into the .so and loads it with
InMemoryDexClassLoader, so there is no dex file on disk for anything to disagree about. Run this
only when Hooker.java changes; hooker_dex.h is checked in, so a plain `gradlew :zygmod:assemble*`
needs neither python nor a JDK.

Nothing here is hardcoded to one machine: the SDK comes from $ANDROID_HOME / $ANDROID_SDK_ROOT /
local.properties / the usual install paths, and javac+java from $JAVA_HOME or PATH (Android
Studio's bundled JBR is the fallback). Missing pieces fail loudly with the name of what to set -
a silently-skipped regeneration would leave the .so carrying a stale callback, which is the kind
of bug that only shows up on the device.
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))


def die(msg):
    sys.exit("make_dex.py: " + msg)


def find_sdk():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        p = os.environ.get(env)
        if p and os.path.isdir(p):
            return p
    # local.properties is what Android Studio writes; this project keeps one per machine.
    for props in (os.path.join(ROOT, "local.properties"),
                  os.path.join(os.path.dirname(ROOT), "local.properties")):
        if os.path.isfile(props):
            m = re.search(r"^sdk\.dir=(.+)$", open(props).read(), re.M)
            if m:
                # local.properties escapes ':' and '\' the Java way.
                p = m.group(1).strip().replace("\\:", ":").replace("\\\\", "\\")
                if os.path.isdir(p):
                    return p
    for p in (os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
              os.path.expanduser("~/Android/Sdk"),
              os.path.expanduser("~/Library/Android/sdk")):
        if os.path.isdir(p):
            return p
    die("Android SDK not found - set ANDROID_HOME, or put sdk.dir in local.properties.")


def newest(parent, want_file):
    """Newest versioned subdirectory of `parent` that actually contains `want_file`."""
    if not os.path.isdir(parent):
        die("missing %s - install it through the SDK Manager." % parent)

    def key(name):
        return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", name)]

    cands = [d for d in sorted(os.listdir(parent), key=key, reverse=True)
             if os.path.isfile(os.path.join(parent, d, want_file))]
    if not cands:
        die("no entry under %s provides %s." % (parent, want_file))
    return os.path.join(parent, cands[0])


def find_jdk_tool(name):
    exe = name + (".exe" if os.name == "nt" else "")
    jh = os.environ.get("JAVA_HOME")
    if jh and os.path.isfile(os.path.join(jh, "bin", exe)):
        return os.path.join(jh, "bin", exe)
    found = shutil.which(name)
    if found:
        return found
    for p in (r"C:\Program Files\Android\Android Studio\jbr\bin",
              "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin",
              "/opt/android-studio/jbr/bin"):
        if os.path.isfile(os.path.join(p, exe)):
            return os.path.join(p, exe)
    die("%s not found - set JAVA_HOME or put a JDK on PATH." % name)


SDK = find_sdk()
JAVAC = find_jdk_tool("javac")
JAVA = find_jdk_tool("java")
ANDROID_JAR = os.path.join(newest(os.path.join(SDK, "platforms"), "android.jar"), "android.jar")
D8JAR = os.path.join(newest(os.path.join(SDK, "build-tools"), os.path.join("lib", "d8.jar")),
                     "lib", "d8.jar")

src = os.path.join(ROOT, "hooker", "io", "laelaps", "zygextdensity", "Hooker.java")
out = os.path.join(ROOT, "hooker_out")
dexout = os.path.join(ROOT, "hooker_dex")
os.makedirs(out, exist_ok=True)
os.makedirs(dexout, exist_ok=True)

print("sdk:      ", SDK)
print("android:  ", ANDROID_JAR)
print("d8:       ", D8JAR)

subprocess.run([JAVAC, "--release", "8", "-cp", ANDROID_JAR, "-d", out, src], check=True)

classes = []
for r, _, fs in os.walk(out):
    for f in fs:
        if f.endswith(".class"):
            classes.append(os.path.join(r, f))
print("classes:", classes)

subprocess.run([JAVA, "-cp", D8JAR, "com.android.tools.r8.D8",
                "--min-api", "28", "--output", dexout] + classes, check=True)

dex = open(os.path.join(dexout, "classes.dex"), "rb").read()
hdr = os.path.join(ROOT, "zygmod", "src", "main", "cpp", "hooker_dex.h")
with open(hdr, "w", newline="\n") as h:
    h.write("// Auto-generated from Hooker.java by make_dex.py. Do not edit.\n#pragma once\n")
    h.write("#include <cstddef>\n\n")
    h.write("static const unsigned char kHookerDex[] = {\n")
    h.write(",".join(str(b) for b in dex))
    h.write("\n};\n")
    h.write("static const size_t kHookerDexLen = %d;\n" % len(dex))
print("dex bytes:", len(dex), "->", hdr)
