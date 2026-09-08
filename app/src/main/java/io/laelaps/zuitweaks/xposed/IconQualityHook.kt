package io.laelaps.zuitweaks.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import java.util.WeakHashMap

/**
 * Sharpens the external taskbar's app icons.
 *
 * Launcher3 keeps one icon raster for the whole process, sized from the *internal*
 * display. Measured on this device: the raster is 198x198, the internal taskbar draws it
 * at 198x198 - pixel perfect - and the external taskbar draws the same bitmap into a
 * 69x69 rect, a 2.87x minification, in `FastBitmapDrawable.drawInternal`'s
 * `canvas.drawBitmap(icon, null, bounds, mPaint)`.
 *
 * Filtering is already on (`Paint(ANTI_ALIAS|FILTER_BITMAP)`) and the density plumbing
 * for the external display is correct, so neither is the problem. What is missing is
 * mipmaps: HWUI has none for these bitmaps, so a 2.87x minification is a single bilinear
 * tap - thin strokes drop out and diagonals stair-step. That is the "drawn in MS Paint"
 * look.
 *
 * The fix pre-scales the bitmap by repeated halving (a cheap box filter) before the
 * drawable ever sees it, but only for icons built against the external taskbar context,
 * so the internal display keeps its pixel-perfect path.
 */
object IconQualityHook {

    private const val BITMAP_INFO = "com.android.launcher3.icons.BitmapInfo"
    private const val FAST_BITMAP_DRAWABLE = "com.android.launcher3.icons.FastBitmapDrawable"

    private var dpContextClass: Class<*>? = null
    private var headroomPercent = 115

    /** Keyed on the shared source bitmap, so each app's icon is scaled once. */
    private val scaled = WeakHashMap<Bitmap, Bitmap>()

    fun install(classLoader: ClassLoader, config: Flags.Config) {
        dpContextClass = XposedHelpers.findClassIfExists(Flags.DP_CONTEXT_CLASS, classLoader)
        headroomPercent = config.int("iconHeadroom", 115)
        if (config.bool("iconProbe", false)) installProbe(classLoader)
        if (config.bool("iconFix", true)) installFix(classLoader)
    }

    /** Confirms the ratio, the bitmap config and whether the window is hardware accelerated. */
    private fun installProbe(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                FAST_BITMAP_DRAWABLE, classLoader, "drawInternal",
                Canvas::class.java, Rect::class.java,
                object : XC_MethodHook() {
                    private var logged = 0

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("icon probe") {
                            if (logged >= 8) return@guard
                            val info = XposedHelpers.getObjectField(param.thisObject, "mBitmapInfo")
                            val bmp = XposedHelpers.getObjectField(info, "icon") as? Bitmap ?: return@guard
                            val rect = param.args[1] as Rect
                            val canvas = param.args[0] as Canvas
                            logged++
                            Logx.i(
                                "icon: src=${bmp.width}x${bmp.height} cfg=${bmp.config} " +
                                    "-> dst=${rect.width()}x${rect.height()} " +
                                    "ratio=${"%.2f".format(bmp.width.toFloat() / rect.width().coerceAtLeast(1))} " +
                                    "hwAccel=${canvas.isHardwareAccelerated}"
                            )
                        }
                    }
                },
            )
            Logx.i("icon probe installed")
        } catch (t: Throwable) {
            Logx.e("icon probe: could not hook $FAST_BITMAP_DRAWABLE.drawInternal", t)
        }
    }

    /**
     * Substitutes a pre-scaled BitmapInfo when the icon is being built for the external
     * taskbar. `newIcon` is re-entered through invokeOriginalMethod so the hook does not
     * recurse into itself.
     */
    private fun installFix(classLoader: ClassLoader) {
        if (dpContextClass == null) {
            Logx.e("icon fix: ${Flags.DP_CONTEXT_CLASS} not found; not installing")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                BITMAP_INFO, classLoader, "newIcon",
                Context::class.java, Int::class.javaPrimitiveType, Path::class.java,
                object : XC_MethodHook() {
                    private var logged = 0

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("icon fix") {
                            val ctx = param.args[0] ?: return@guard
                            if (dpContextClass?.isInstance(ctx) != true) return@guard

                            val profile = XposedHelpers.callMethod(ctx, "getDeviceProfile")
                            val iconSize = XposedHelpers.getIntField(profile, "taskbarIconSize")
                            if (iconSize <= 0) return@guard
                            // The drawable's bounds at draw time are larger than this
                            // field - measured 69 against a taskbarIconSize of 60 - so
                            // scaling to the field alone ends in an upscale and softens
                            // the icon. 115% lands on 69, which makes the draw a 1:1 blit
                            // with no resampling at all; that measured sharpest of the
                            // three settings tried (60 / 90 / 69).
                            val target = iconSize * headroomPercent / 100

                            val src = XposedHelpers.getObjectField(param.thisObject, "icon") as? Bitmap
                                ?: return@guard
                            // Only worth doing for a real minification; 1.5x is where a
                            // single bilinear tap starts visibly dropping detail.
                            if (src.width < target * 3 / 2) return@guard

                            // downscale() hands the source straight back when it could not
                            // take a software copy of a hardware bitmap - there is nothing to
                            // pre-scale then, so leave this icon on the original path rather
                            // than caching src->src and substituting a BitmapInfo for nothing.
                            val small = synchronized(scaled) {
                                scaled[src]?.takeIf { !it.isRecycled }
                                    ?: downscale(src, target).takeIf { it !== src }?.also { scaled[src] = it }
                            } ?: return@guard

                            val copy = XposedHelpers.callMethod(param.thisObject, "clone")
                            try {
                                XposedHelpers.callMethod(copy, "setIcon", small)
                            } catch (t: Throwable) {
                                XposedHelpers.setObjectField(copy, "icon", small)
                            }
                            param.result = XposedBridge.invokeOriginalMethod(param.method, copy, param.args)

                            if (logged < 4) {
                                logged++
                                Logx.i("icon fix: ${src.width}px -> ${small.width}px for ${ctx.javaClass.simpleName}")
                            }
                        }
                    }
                },
            )
            Logx.i("icon fix installed (external taskbar icons pre-scaled)")
        } catch (t: Throwable) {
            Logx.e("icon fix: could not hook $BITMAP_INFO.newIcon", t)
        }
    }

    /**
     * Halving repeatedly averages 4 source pixels per step, which is what a mipmap chain
     * would do; one createScaledBitmap straight from 198 to 90 would sample just as
     * sparsely as the draw we are replacing.
     */
    private fun downscale(src: Bitmap, target: Int): Bitmap {
        // Hardware bitmaps cannot be read back, so take a software copy first. copy() returns
        // null when it cannot allocate one; returning [src] says "nothing was done" and the
        // caller skips this icon rather than dereferencing null on the next line.
        var cur = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: return src
        } else {
            src
        }
        var owned = cur !== src

        while (cur.width / 2 >= target && cur.width / 2 > 0) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (owned) cur.recycle()
            cur = next
            owned = true
        }
        if (cur.width != target) {
            val next = Bitmap.createScaledBitmap(cur, target, target, true)
            if (owned) cur.recycle()
            cur = next
        }
        return cur
    }
}
