// Zygisk module: systemwide external-display tablet fix.
// STEP 2a: wire up LSPlant (init only), gated to the dispprobe test process so a failure is
// isolated to that one app (never boot, never system_server, never other apps).
#include <android/log.h>
#include <jni.h>
#include <sys/types.h>
#include <string>
#include <string_view>
#include <cstdio>
#include <cstdint>
#include <sys/system_properties.h>
#include <xdl.h>
#include <dobby.h>
#include "zygisk.hpp"
#include "hooker_dex.h"

import lsplant;

using zygisk::Api;
using zygisk::AppSpecializeArgs;



#define LOG_TAG "ExtDensityZ"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Inline hooker/unhooker LSPlant needs (its InitInfo backend), backed by Dobby (thread-safe).
static void *InlineHooker(void *target, void *hooker) {
    void *origin = nullptr;
    if (DobbyHook(target, hooker, &origin) == 0) return origin;
    return nullptr;
}
static bool InlineUnhooker(void *func) {
    return DobbyDestroy(func) == 0;
}

static bool g_lsplant_inited = false;
static bool g_hooked = false;

static bool initLSPlant(JNIEnv *env) {
    void *art = xdl_open("libart.so", XDL_TRY_FORCE_LOAD);
    if (!art) {
        LOGE("xdl_open(libart.so) failed");
        return false;
    }
    lsplant::InitInfo info{
        .inline_hooker = InlineHooker,
        .inline_unhooker = InlineUnhooker,
        .art_symbol_resolver = [art](std::string_view name) -> void * {
            std::string s(name);
            size_t sz = 0;
            void *p = xdl_sym(art, s.c_str(), &sz);
            if (!p) p = xdl_dsym(art, s.c_str(), &sz);
            return p;
        },
        .art_symbol_prefix_resolver = [art](std::string_view prefix) -> void * {
            // xDL has no prefix search; best effort via exact match (may return null).
            std::string s(prefix);
            size_t sz = 0;
            void *p = xdl_sym(art, s.c_str(), &sz);
            if (!p) p = xdl_dsym(art, s.c_str(), &sz);
            return p;
        },
    };
    bool ok = lsplant::Init(env, info);
    LOGI("lsplant::Init -> %d", ok ? 1 : 0);
    return ok;
}

static bool jniFailed(JNIEnv *env, const char *what) {
    if (env->ExceptionCheck()) {
        LOGE("JNI exception at %s", what);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

// Loads the embedded Hooker dex and hooks Display.getRealMetrics(DisplayMetrics) via LSPlant.
static bool installHook(JNIEnv *env) {
    jobject dexBuf = env->NewDirectByteBuffer((void *) kHookerDex, (jlong) kHookerDexLen);
    if (jniFailed(env, "NewDirectByteBuffer") || !dexBuf) return false;

    jclass cLoaderCls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (jniFailed(env, "FindClass InMemoryDexClassLoader") || !cLoaderCls) return false;
    jmethodID cLoaderCtor =
        env->GetMethodID(cLoaderCls, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (jniFailed(env, "InMemoryDexClassLoader.<init>")) return false;
    // parent = boot classloader (null); Hooker only references framework classes.
    jobject loader = env->NewObject(cLoaderCls, cLoaderCtor, dexBuf, nullptr);
    if (jniFailed(env, "new InMemoryDexClassLoader") || !loader) return false;

    jmethodID loadClass =
        env->GetMethodID(cLoaderCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring hookerName = env->NewStringUTF("io.laelaps.zygextdensity.Hooker");
    jclass hookerCls = (jclass) env->CallObjectMethod(loader, loadClass, hookerName);
    if (jniFailed(env, "loadClass Hooker") || !hookerCls) return false;

    jmethodID hookerCtor = env->GetMethodID(hookerCls, "<init>", "()V");
    jobject hooker = env->NewObject(hookerCls, hookerCtor);
    if (jniFailed(env, "new Hooker") || !hooker) return false;

    // callback = Hooker.getDeclaredMethod("callback", Object[].class)
    jclass classCls = env->FindClass("java/lang/Class");
    jmethodID getDeclaredMethod = env->GetMethodID(
        classCls, "getDeclaredMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jclass objArrCls = env->FindClass("[Ljava/lang/Object;");
    jobjectArray cbParams = env->NewObjectArray(1, classCls, (jobject) objArrCls);
    jstring cbName = env->NewStringUTF("callback");
    jobject callback = env->CallObjectMethod((jobject) hookerCls, getDeclaredMethod, cbName, cbParams);
    if (jniFailed(env, "getDeclaredMethod callback") || !callback) return false;

    // target = Display.getDeclaredMethod("getRealMetrics", DisplayMetrics.class)
    jclass displayCls = env->FindClass("android/view/Display");
    jclass dmCls = env->FindClass("android/util/DisplayMetrics");
    jobjectArray tParams = env->NewObjectArray(1, classCls, (jobject) dmCls);
    jstring tName = env->NewStringUTF("getRealMetrics");
    jobject target = env->CallObjectMethod((jobject) displayCls, getDeclaredMethod, tName, tParams);
    if (jniFailed(env, "getDeclaredMethod getRealMetrics") || !target) return false;

    jobject backup = lsplant::Hook(env, target, hooker, callback);
    if (jniFailed(env, "lsplant::Hook") || !backup) {
        LOGE("lsplant::Hook returned null");
        return false;
    }

    jfieldID backupField = env->GetFieldID(hookerCls, "backup", "Ljava/lang/reflect/Method;");
    env->SetObjectField(hooker, backupField, backup);
    if (jniFailed(env, "set backup")) return false;

    env->NewGlobalRef(hooker); // keep the hooker (and its backup) alive for the process lifetime
    LOGI("Display.getRealMetrics hooked via LSPlant");
    return true;
}

// ---- allowlist via system properties (no companion; readable directly from the app process) ----
//
// The ZUI Tweaks app sets one boolean prop per selected package through root (resetprop):
//   persist.zui.extdensity.<fnv1a(pkg)> = 1
// The module reads it here. A hashed 8-hex suffix keeps the prop name short and fixed-length, and
// one prop per package sidesteps the value-length limit. No cross-app file access, no socket, and
// the change is live: adding an app then restarting it applies with no reboot.
// The Kotlin side (ExtDensityTargets.propName) MUST compute the identical name.
static constexpr const char *kPropPrefix = "persist.zui.extdensity.";

static std::string propName(const std::string &pkg) {
    uint32_t h = 2166136261u; // FNV-1a offset basis
    for (unsigned char c : pkg) {
        h ^= c;
        h *= 16777619u; // FNV-1a prime
    }
    char buf[48];
    snprintf(buf, sizeof(buf), "%s%08x", kPropPrefix, h);
    return std::string(buf);
}

static bool isSelected(const std::string &pkg) {
    char val[PROP_VALUE_MAX] = {0};
    __system_property_get(propName(pkg).c_str(), val);
    return val[0] == '1';
}

// The package name, taken from the app's data-dir basename (/data/user/<u>/<pkg>). Unlike
// nice_name this stays the package even for a process with a custom android:process; the picker
// keys the allowlist on package, so they must agree. Falls back to nice_name if unavailable.
static std::string packageOf(JNIEnv *env, const AppSpecializeArgs *args) {
    if (!args) return "";
    auto read = [&](jstring s) -> std::string {
        if (!s) return "";
        const char *c = env->GetStringUTFChars(s, nullptr);
        std::string out = c ? c : "";
        if (c) env->ReleaseStringUTFChars(s, c);
        return out;
    };
    std::string dir = read(args->app_data_dir);
    if (!dir.empty()) {
        size_t slash = dir.find_last_of('/');
        std::string base = (slash == std::string::npos) ? dir : dir.substr(slash + 1);
        if (!base.empty()) return base;
    }
    return read(args->nice_name);
}

class ExtDensityModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *, JNIEnv *env) override {
        this->env = env;
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        // Never touch child-zygotes (WebView/app zygote); they fork further processes.
        if (args && args->is_child_zygote && *args->is_child_zygote) return;

        // Safety: only hook AFTER boot completes. Processes that start during boot (SystemUI,
        // launcher, persistent system apps) are left untouched, so a hook problem can never
        // break the boot itself - only an app the user launches afterwards, which is recoverable.
        // The apps this fix targets (Laftel, browsers, ...) are launched post-boot anyway.
        char boot[PROP_VALUE_MAX] = {0};
        __system_property_get("sys.boot_completed", boot);
        if (boot[0] != '1') return;

        // Only apps the user selected in ZUI Tweaks (a per-package property set by the app). Not
        // selected -> do nothing, so other apps pay zero cost and carry zero risk. A selection
        // change applies on the app's next start, no reboot.
        std::string pkg = packageOf(env, args);
        if (pkg.empty() || !isSelected(pkg)) return;

        LOGI("selected app %s: installing external-display fix", pkg.c_str());
        if (!g_lsplant_inited) g_lsplant_inited = initLSPlant(env);
        if (g_lsplant_inited && !g_hooked) {
            if (installHook(env)) {
                g_hooked = true;
                LOGI("hooked in %s", pkg.c_str());
            }
        }
    }

private:
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(ExtDensityModule)
