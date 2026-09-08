package io.laelaps.zuitweaks.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View

/**
 * Renders our persisted folders (FolderStore) into the drawer's app grid — grounded in the
 * bytecode spec from the drawer-rendering recon:
 *
 *  - Hook AlphabeticalAppsList.updateAdapterItems(): in `before`, read the ordered app-list field
 *    `d`, build a transformed copy that DROPS member apps and INSERTS one synthetic folder AppInfo
 *    per group, and swap it into `d`; in `after`, restore the launcher's real list. The launcher's
 *    own loop then wraps every entry (incl. ours) via AdapterItem.asApp -> a normal BubbleTextView,
 *    and DiffUtil repaints. No new adapter view type.
 *  - The folder entry is a synthetic AppInfo (sentinel component carrying the group id, folder name
 *    title, a composite preview icon). Tapping it goes through Launcher.startActivitySafely, which
 *    we hook to open our folder overlay instead of launching.
 *  - After FolderStore changes, refresh() calls onAppsUpdated() on the UI thread.
 *
 * Fragile (R8) names for THIS apk version: the `d` field. Everything else is a retained name.
 */
object DrawerFolderRender {

    private const val ALPHA_LIST = "com.android.launcher3.allapps.AlphabeticalAppsList"
    private const val APP_INFO = "com.android.launcher3.model.data.AppInfo"
    private const val BITMAP_INFO = "com.android.launcher3.icons.BitmapInfo"
    private const val FAST_BITMAP_DRAWABLE = "com.android.launcher3.icons.FastBitmapDrawable"
    private const val LAUNCHER = "com.android.launcher3.Launcher"
    private const val ORDERED_APPS_FIELD = "d"   // AlphabeticalAppsList ordered List<AppInfo>

    /** Sentinel package that marks a synthetic folder AppInfo; class name = "g" + groupId. */
    const val SENTINEL_PKG = "io.laelaps.zuitweaks.folder"

    @Volatile private var classLoader: ClassLoader? = null
    // Every AlphabeticalAppsList we've seen (ZUI has several: main drawer, taskbar nav, etc.), so a
    // refresh repaints the one the user is actually looking at rather than a guess.
    private val alphaLists = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
    @Volatile private var storeReady = false
    @Volatile private var launcherContext: Context? = null

    // Member AppInfos per group from the latest transform, so a folder tap can open the overlay.
    private val groupMembers = HashMap<Long, List<Any>>()

    // Custom drawer ordering (config `drawerorder=1`): impose a persisted slot order on the drawer,
    // new apps appended at the end. `maxDrawerOutSize` tracks the fullest list seen so we only SEED /
    // append from the main drawer, not a smaller (taskbar) AlphabeticalAppsList.
    @Volatile private var drawerOrderEnabled = false
    @Volatile private var maxDrawerOutSize = 0

    // --- icon-size diagnostics (config `foldericonprobe=1`) -------------------------------
    @Volatile private var probeEnabled = false
    // identityHashCode of the BitmapInfo we last baked for a folder. The draw probe matches on
    // it — the FastBitmapDrawable's mBitmapInfo IS this very object (BitmapInfo.newIcon keeps the
    // reference) — to pick our folder out of the grid and log its draw box `l` vs raster `S`,
    // first-open vs after-overlay-close.
    @Volatile private var lastFolderBitmapInfoId = 0

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun install(cl: ClassLoader, probe: Boolean = false, drawerOrder: Boolean = false, previewGrid: Int = 2) {
        previewSide = previewGrid.coerceIn(2, 4)
        classLoader = cl
        drawerOrderEnabled = drawerOrder
        hookListTransform(cl)
        hookClick(cl)
        installIconScaleFix(cl)
        if (drawerOrder) {
            installReorderHideHook(cl)
            installHideFastScroll(cl)
        }
        if (probe) installIconProbe(cl)
        Logx.i("drawer folder render: installed (iconProbe=$probe drawerOrder=$drawerOrder)")
    }

    /**
     * Hide the A-Z fast scroller / index (meaningless under a custom order): skip the scroller view's
     * draw so its thumb and letter popup don't paint. Tries the known Launcher3 class names.
     */
    private fun installHideFastScroll(cl: ClassLoader) {
        val names = listOf(
            "com.android.launcher3.views.RecyclerViewFastScroller",
            "com.android.launcher3.BaseRecyclerViewFastScroller",
            "com.android.launcher3.allapps.AllAppsFastScrollHelper",
        )
        var hooked = 0
        for (n in names) {
            val c = XposedHelpers.findClassIfExists(n, cl) ?: continue
            runCatching {
                XposedBridge.hookAllMethods(c, "draw", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (drawerOrderEnabled) param.setResult(null)
                    }
                })
                hooked++
                Logx.i("drawer order: hid fast scroller ($n)")
            }
        }
        if (hooked == 0) Logx.i("drawer order: no fast-scroller class found to hide")
    }

    /**
     * While a live drawer reorder is in flight, hide the dragged item's GRID view (the launcher shows
     * a separate floating drag view), so the user doesn't see the icon twice — original slot + the
     * floating copy. Runs on every BubbleTextView bind; cheap when not reordering.
     */
    private fun installReorderHideHook(cl: ClassLoader) {
        runCatching {
            val btv = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", cl)
            for (name in arrayOf("applyFromApplicationInfo", "applyFromItemInfoWithIcon")) {
                runCatching {
                    XposedBridge.hookAllMethods(btv, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logx.guard("reorder hide") {
                                val view = param.thisObject as? View ?: return@guard
                                val ds = draggedSlot
                                val hide = reorderActive && ds != null &&
                                    componentOf(param.args?.getOrNull(0) ?: return@guard) == ds
                                val want = if (hide) View.INVISIBLE else View.VISIBLE
                                if (view.visibility != want) view.visibility = want
                            }
                        }
                    })
                }
            }
            Logx.i("drawer reorder: hide-dragged-view hook installed")
        }.onFailure { Logx.e("drawer reorder: hide hook install failed", it) }
    }

    /**
     * Keep the folder icon from shrinking after a tap. The drawer folder is a normal BubbleTextView,
     * so tapping it runs a press/scale animation on its FastBitmapDrawable — but we hijack the tap
     * (startActivitySafely) to open our overlay, so that animation is never reversed and the
     * drawable's current-scale field sticks below 1.0. Measured: field `d` drifting 1.0 -> ~0.8 for
     * the folder while neighbouring apps stay at 1.0, with bitmap/box/content-fraction all identical
     * to apps. Fix: for our folder drawables only, pin the scale to 1.0 before every draw (O(1) id
     * check; folder icons need no press bounce — we own their tap).
     */
    private fun installIconScaleFix(cl: ClassLoader) {
        runCatching {
            val fbd = XposedHelpers.findClass(FAST_BITMAP_DRAWABLE, cl)
            val f = fbd.declaredFields.firstOrNull {
                it.type == java.lang.Float.TYPE &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    !it.name.contains("lpha", ignoreCase = true) &&
                    !it.name.contains("isabled", ignoreCase = true)
            }?.apply { isAccessible = true }
            if (f == null) { Logx.e("icon scale-fix: scale field not found; not installing"); return }
            Logx.i("icon scale-fix: scale field = ${f.name}")
            XposedHelpers.findAndHookMethod(fbd, "draw", android.graphics.Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val suppressAll = iconPressSuppressed()
                    if (!suppressAll && boundFolderBiIds.isEmpty()) return
                    Logx.guard("icon scale-fix") {
                        val self = param.thisObject
                        val isFolder = boundFolderBiIds.isNotEmpty() && run {
                            val bi = XposedHelpers.getObjectField(self, "mBitmapInfo")
                            bi != null && boundFolderBiIds.contains(System.identityHashCode(bi))
                        }
                        // Folder icons: always pinned (we own their tap, no press bounce). Every drawer
                        // icon: pinned WHILE A DRAG IS LIVE so a hovered/displaced app can't press-scale
                        // flicker (repeatedly shrink and pop) during a reorder or an external drag.
                        if (isFolder || suppressAll) {
                            val cur = f.getFloat(self)
                            if (cur != 1.0f) {
                                if (suppressAll && !isFolder && iconScaleFlickerLog) {
                                    Logx.i("icon scale-fix: pinned drag-time press-scale $cur -> 1.0")
                                }
                                f.setFloat(self, 1.0f)
                            }
                        }
                    }
                }
            })
            Logx.i("icon scale-fix installed (folder icons pinned; all drawer icons pinned during a drag)")
        }.onFailure { Logx.e("icon scale-fix install failed", it) }
    }

    /** Rebuild every drawer list so folder changes appear. Safe to call from any thread. */
    fun refresh() {
        val lists = alphaLists.toList()
        Logx.i("render refresh: ${lists.size} alphaList instance(s)")
        mainHandler.post {
            for (al in lists) {
                runCatching { XposedHelpers.callMethod(al, "onAppsUpdated") }
                // onAppsUpdated diffs via DiffUtil, which treats a renamed folder (same sentinel
                // component) as unchanged and skips the label rebind. Force a full rebind.
                val adapter = adapterOf(al)
                runCatching { adapter?.let { XposedHelpers.callMethod(it, "notifyDataSetChanged") } }
                Logx.i("render refresh: al=${al.javaClass.simpleName} adapter=${adapter?.javaClass?.simpleName}")
            }
        }
    }

    /** The all-apps RecyclerView.Adapter held by the AlphabeticalAppsList (for a forced rebind). */
    private fun adapterOf(al: Any): Any? {
        var cls: Class<*>? = al.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                f.isAccessible = true
                val v = runCatching { f.get(al) }.getOrNull() ?: continue
                if (v.javaClass.name.contains("Adapter", ignoreCase = true) &&
                    hasMethod(v.javaClass, "notifyDataSetChanged")
                ) return v
            }
            cls = cls.superclass
        }
        return null
    }

    private fun hasMethod(cls: Class<*>, name: String): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.declaredMethods.any { it.name == name && it.parameterCount == 0 }) return true
            c = c.superclass
        }
        return false
    }

    // ---------------------------------------------------- 1) list transform

    private fun hookListTransform(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(ALPHA_LIST, cl)
            XposedBridge.hookAllMethods(cls, "updateAdapterItems", object : XC_MethodHook() {
                // Runs on the UI thread only, so before->original->after is synchronous for one
                // call and a single field safely carries the saved list. It is NOT one call per
                // gesture though: hookAllMethods puts this same callback instance on every
                // overload, and the no-arg overload calls the other one. Only the outermost
                // frame may transform and restore - an inner frame would re-transform the
                // already-transformed list and steal the outer frame's saved original.
                private var savedD: Any? = null
                private var depth = 0

                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Outside the guard, and unconditional, so depth stays balanced with the
                    // matching decrement even if anything below throws.
                    depth++
                    if (depth != 1) return
                    savedD = null
                    Logx.guard("updateAdapterItems:transform") {
                        alphaLists.add(param.thisObject)
                        ensureStore(param.thisObject)
                        val searching = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "hasSearchResults") as? Boolean
                        }.getOrNull() ?: false
                        if (searching) return@guard
                        val groups = FolderStore.listGroups()
                        if (groups.isEmpty() && !drawerOrderEnabled) return@guard
                        @Suppress("UNCHECKED_CAST")
                        val d = runCatching { XposedHelpers.getObjectField(param.thisObject, ORDERED_APPS_FIELD) as? List<Any> }.getOrNull()
                        if (d == null) { Logx.i("render: field '$ORDERED_APPS_FIELD' not a List; skip"); return@guard }
                        val transformed = transform(d, groups, cl)
                        savedD = d
                        XposedHelpers.setObjectField(param.thisObject, ORDERED_APPS_FIELD, transformed)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (depth > 0) depth--
                    if (depth != 0) return
                    Logx.guard("updateAdapterItems:restore") {
                        val saved = savedD ?: return@guard
                        savedD = null
                        XposedHelpers.setObjectField(param.thisObject, ORDERED_APPS_FIELD, saved)
                    }
                }
            })
            Logx.i("drawer folder render: hooked $ALPHA_LIST.updateAdapterItems")
        } catch (t: Throwable) {
            Logx.e("drawer folder render: list-transform install failed", t)
        }
    }

    /** Drop member apps; insert one synthetic folder AppInfo at each group's first-member spot. */
    /**
     * An app dragged out of an open folder whose drag has not been released yet.
     *
     * It is still a member in [FolderStore] - whether it really leaves is only decided when the
     * drag ends - but it must already render as an ordinary top-level icon, because that is what
     * the drawer reorder positions and what the user is dragging. So membership is suppressed here
     * for the length of the gesture rather than by writing the database early.
     */
    @Volatile var pendingDragOut: String? = null

    private fun transform(d: List<Any>, groups: List<FolderStore.Group>, cl: ClassLoader): List<Any> {
        val compToGroup = HashMap<String, FolderStore.Group>()
        for (g in groups) for (c in g.members) compToGroup[c] = g
        // Out of the folder for now: top-level icon, and absent from the folder's own preview.
        pendingDragOut?.let { compToGroup.remove(it) }

        // collect member AppInfos per group (in drawer order) for the preview + overlay
        val membersByGroup = HashMap<Long, ArrayList<Any>>()
        for (app in d) {
            val g = compToGroup[componentOf(app)] ?: continue
            membersByGroup.getOrPut(g.id) { ArrayList() }.add(app)
        }
        // Order each group's members by the user's stored order (FolderStore returns group.members in
        // `ord` order), so the folder opens and previews in the chosen order rather than alphabetical.
        val ordIndexByGroup = HashMap<Long, Map<String, Int>>()
        for (g in groups) ordIndexByGroup[g.id] = g.members.withIndex().associate { (i, c) -> c to i }
        membersByGroup.forEach { (gid, list) ->
            val ord = ordIndexByGroup[gid] ?: return@forEach
            list.sortBy { ord[componentOf(it)] ?: Int.MAX_VALUE }
        }
        // Cache members per group for the tap->overlay path. Do NOT clear: ZUI has several
        // AlphabeticalAppsList instances (main drawer, taskbar, ...) and each runs this transform.
        // A secondary list that lacks these apps must not wipe the main drawer's cache — that made a
        // folder icon still render (from the main list) yet open to "no members" because a later
        // list cleared the cache (an intermittent "folder won't open"). Prune only dead groups,
        // then merge THIS list's members (overwriting per-group only for groups this list has).
        val liveIds = groups.mapTo(HashSet()) { it.id }
        groupMembers.keys.retainAll(liveIds)
        membersByGroup.forEach { (id, list) -> groupMembers[id] = list }

        val emitted = HashSet<Long>()
        val out = ArrayList<Any>(d.size)
        for (app in d) {
            val g = compToGroup[componentOf(app)]
            if (g == null) { out.add(app); continue }
            if (emitted.add(g.id)) {
                buildFolderAppInfo(g, membersByGroup[g.id] ?: emptyList(), cl)?.let { out.add(it) }
            }
            // member itself is dropped
        }
        return if (drawerOrderEnabled) imposeDrawerOrder(out, membersByGroup) else out
    }

    /**
     * Reorder the drawer entries by the user's persisted order (FolderStore.drawerOrder). Entries not
     * yet in the order (newly installed apps) append to the end and are persisted, so the order is
     * stable and new apps land last. A folder that isn't a stored slot yet takes the position of its
     * first member, so creating a folder doesn't jump it to the end. Seed/append only from the fullest
     * list seen (the main drawer), not a smaller taskbar list.
     */
    private fun imposeDrawerOrder(out: List<Any>, membersByGroup: Map<Long, List<Any>>): List<Any> {
        if (out.isEmpty()) return out
        val customized = FolderStore.drawerOrderCustomized()
        // Default (not yet customized): mirror the FULLEST list's natural (alphabetical) order, so the
        // drawer looks normal until the user first reorders. Reseed whenever a fuller list appears —
        // this also fixes a tiny early list (e.g. 1 app) seeding a bogus first slot.
        if (!customized && !reorderActive && out.size >= maxDrawerOutSize) {
            maxDrawerOutSize = out.size
            // `>=` means the same list re-seeds on every updateAdapterItems, and this runs on
            // the UI thread during a list rebuild - so only write when the order actually
            // changed rather than persisting an identical list on every drawer refresh.
            val seed = out.map { componentOf(it) }
            if (seed != FolderStore.drawerOrder()) FolderStore.setDrawerOrder(seed)
        }
        // During a live reorder drag, sort by the in-flight drag order instead of the persisted one.
        val persisted = if (reorderActive) synchronized(dragOrder) { dragOrder.toList() } else FolderStore.drawerOrder()
        val pos = HashMap<String, Int>(persisted.size)
        persisted.forEachIndexed { i, s -> pos[s] = i }
        var nextNew = persisted.size
        val newAppSlots = ArrayList<String>()
        val newFolderInserts = ArrayList<Pair<String, String?>>()   // folderSlot, firstMemberSlot
        val ranked = out.mapIndexed { idx, app ->
            val key = componentOf(app)
            val order: Double = when {
                pos.containsKey(key) -> pos[key]!!.toDouble()
                folderGroupIdOf(key) != null -> {
                    // new folder: sit just before its first member's slot (if any), else append
                    val gid = folderGroupIdOf(key)!!
                    val firstMemberKey = membersByGroup[gid]?.firstOrNull()?.let { componentOf(it) }
                    newFolderInserts.add(key to firstMemberKey)
                    val mp = firstMemberKey?.let { pos[it] }
                    if (mp != null) mp - 0.5 else (nextNew++).toDouble()
                }
                else -> { newAppSlots.add(key); (nextNew++).toDouble() }
            }
            Triple(order, idx, app)
        }
        val sorted = ranked.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        // Persist any newly-seen slot (from the fullest list) so EVERY app and folder is a first-class,
        // reorderable slot: an app appends at the end, a folder inserts just before its first member.
        // Without this, a folder created after the order was customized never entered the order and so
        // could not be reordered (the "one folder won't move" bug).
        if (!reorderActive && (newAppSlots.isNotEmpty() || newFolderInserts.isNotEmpty()) && out.size >= maxDrawerOutSize) {
            maxDrawerOutSize = out.size
            val updated = ArrayList(persisted)
            for ((folderSlot, memberSlot) in newFolderInserts) {
                if (updated.contains(folderSlot)) continue
                val i = memberSlot?.let { updated.indexOf(it) } ?: -1
                if (i >= 0) updated.add(i, folderSlot) else updated.add(folderSlot)
            }
            updated.addAll(newAppSlots.filter { !updated.contains(it) })
            if (updated != persisted) {
                FolderStore.setDrawerOrder(updated)
                Logx.i("drawer order: +${newAppSlots.size} app, +${newFolderInserts.size} folder slot(s)")
            }
        }
        return sorted
    }

    // ---------------------------------------------------- live drawer reorder (drag)

    @Volatile private var reorderActive = false
    @Volatile private var draggedSlot: String? = null
    private val dragOrder = ArrayList<String>()
    @Volatile private var lastReorderKey = ""

    // Set true by DrawerLongPressHook while an EXTERNAL (SecondaryDragController) drawer drag is in
    // flight — that path doesn't use our internal reorder, but a hovered icon can still press-scale
    // flicker there. Together with reorderActive it gates the drag-time press-scale pin below.
    @Volatile private var externalDragActive = false
    fun setExternalDragActive(active: Boolean) { externalDragActive = active }

    // Diagnostic: log each time the drag-time pin catches a non-folder icon mid press-scale (proves the
    // flicker source and that it is now suppressed). Bounded to drag duration; leave on to verify.
    private val iconScaleFlickerLog = true

    /** True while any drawer drag is live (internal reorder or external drag) → pin icon press-scale. */
    private fun iconPressSuppressed(): Boolean = reorderActive || externalDragActive

    fun isReordering(): Boolean = reorderActive

    /** Diagnostic snapshot of the reorder state. */
    fun debugState(): String =
        "reorderActive=$reorderActive draggedSlot=$draggedSlot dragOrder=${synchronized(dragOrder) { dragOrder.size }} orderEnabled=$drawerOrderEnabled"

    /** Begin a live drawer reorder of [app] (an AppInfo or synthetic folder AppInfo). */
    fun beginReorder(app: Any): Boolean = beginReorderSlot(componentOf(app))

    /**
     * Begin a live drawer reorder of the given drawer [slot] key directly. Split out from
     * [beginReorder] because a folder member's tag is a WorkspaceItemInfo, whose slot key must be read
     * via getTargetComponent() (the caller's componentKey), NOT the `componentName` field componentOf
     * relies on — the two agree in value but not in how they are read.
     */
    fun beginReorderSlot(slot: String): Boolean {
        if (!drawerOrderEnabled) return false
        val order = FolderStore.drawerOrder()
        if (slot.isEmpty() || !order.contains(slot)) return false
        synchronized(dragOrder) { dragOrder.clear(); dragOrder.addAll(order) }
        draggedSlot = slot
        lastReorderKey = ""
        reorderActive = true
        Logx.i("drawer reorder: begin '$slot'")
        return true
    }

    /**
     * Engage a live drawer reorder for an app just dragged OUT of a folder. The app is a folder member
     * transitioning to a drawer item mid-drag: it is NOT in the drawer slot order yet, so beginReorder's
     * "order must contain the slot" guard would reject it. Seed the in-memory drag order with the slot
     * appended so the normal handleReorderMove → updateReorder reflow applies to it — but do NOT persist
     * that here. A release that lands in the drawer commits it (commitReorder writes dragOrder, incl.
     * this slot, and marks the order customized); a release that hands off to HOME cancels the reorder,
     * leaving the persisted order untouched so the app reverts to its natural drawer position instead of
     * being spuriously pinned. The app's own grid view is hidden by installReorderHideHook (it is the
     * draggedSlot), so a refresh() that renders it top-level does not show two icons.
     */
    fun beginDragOutReorder(slot: String): Boolean {
        if (!drawerOrderEnabled || slot.isEmpty()) return false
        val order = FolderStore.drawerOrder().toMutableList()
        if (!order.contains(slot)) order.add(slot)
        synchronized(dragOrder) { dragOrder.clear(); dragOrder.addAll(order) }
        draggedSlot = slot
        lastReorderKey = ""
        reorderActive = true
        Logx.i("drawer reorder: begin (drag-out) '$slot' order=${order.size}")
        return true
    }

    /** Move the dragged slot to sit before/after [targetSlot]; reflow the grid (DiffUtil animates). */
    fun updateReorder(targetSlot: String, insertAfter: Boolean) {
        if (!reorderActive) return
        val slot = draggedSlot ?: return
        if (targetSlot == slot || targetSlot.isEmpty()) return
        val key = "$targetSlot|$insertAfter"
        if (key == lastReorderKey) return
        lastReorderKey = key
        synchronized(dragOrder) {
            dragOrder.remove(slot)
            var ti = dragOrder.indexOf(targetSlot)
            if (ti < 0) { dragOrder.add(slot); return }
            if (insertAfter) ti++
            dragOrder.add(ti.coerceIn(0, dragOrder.size), slot)
        }
        reflowLists()
    }

    /** Persist the drag order (marks the drawer customized) and repaint. */
    fun commitReorder() {
        if (!reorderActive) return
        val order = synchronized(dragOrder) { dragOrder.toList() }
        reorderActive = false
        draggedSlot = null
        FolderStore.setDrawerOrder(order)
        FolderStore.setDrawerOrderCustomized()
        refresh()
        Logx.i("drawer reorder: committed (${order.size} slots)")
    }

    /** Abandon the reorder (grouped, or dragged to home); restore the persisted order. */
    fun cancelReorder() {
        if (!reorderActive) return
        reorderActive = false
        draggedSlot = null
        refresh()
        Logx.i("drawer reorder: cancelled")
    }

    /** onAppsUpdated on every alpha list, WITHOUT notifyDataSetChanged, so DiffUtil animates the move. */
    private fun reflowLists() {
        mainHandler.post {
            for (al in alphaLists.toList()) runCatching { XposedHelpers.callMethod(al, "onAppsUpdated") }
        }
    }

    // ---------------------------------------------------- 2) synthetic folder AppInfo

    // Cache the synthetic folder AppInfo so transform reuses the SAME instance while nothing about the
    // folder changed. Rebuilding it every transform re-rastered the icon and made DiffUtil churn the
    // folder rows — which broke the smoothness of a live reorder reflow. Keyed by a signature of the
    // name + ordered member components.
    private val folderInfoCache = HashMap<Long, Pair<String, Any>>()

    private fun buildFolderAppInfo(group: FolderStore.Group, members: List<Any>, cl: ClassLoader): Any? {
        val sig = group.name + "|" + members.joinToString(",") { componentOf(it) }
        folderInfoCache[group.id]?.let { if (it.first == sig) return it.second }
        return try {
            val appInfoCls = XposedHelpers.findClass(APP_INFO, cl)
            val ctor = appInfoCls.getDeclaredConstructor().apply { isAccessible = true }
            val app = ctor.newInstance()
            XposedHelpers.setObjectField(app, "componentName", ComponentName(SENTINEL_PKG, "g" + group.id))
            XposedHelpers.setObjectField(app, "title", group.name)
            XposedHelpers.setObjectField(app, "user", Process.myUserHandle())
            XposedHelpers.setObjectField(app, "intent", Intent())
            // Build the icon at the launcher's canonical size, content pre-inset to match apps, and
            // wrap via createIconBitmap (no white plate, no re-normalisation) so it is stable across
            // binds. If we cannot (no context / icons not ready), DON'T emit — leaving a low-res
            // bitmap would let verifyHighRes clobber it via an icon-cache lookup on our sentinel.
            val info = buildFolderBitmapInfo(cl, members) ?: return null
            XposedHelpers.setObjectField(app, "bitmap", info)
            folderInfoCache[group.id] = sig to app
            app
        } catch (t: Throwable) {
            Logx.e("render: buildFolderAppInfo failed", t); null
        }
    }

    /**
     * Folder icon at the launcher's canonical raster size S, with the composite confined to the
     * SAME opaque region a real member icon fills. On-screen size = l(container) × contentFraction;
     * matching a real member's contentFraction makes the folder track app-icon size in EVERY
     * container (main drawer, taskbar, secondary) regardless of that container's icon box l — which
     * was the "shrinks on reopen" cause. Deferred while members are low-res (else verifyHighRes
     * would clobber our icon via an icon-cache lookup on the sentinel component).
     */
    private fun buildFolderBitmapInfo(cl: ClassLoader, members: List<Any>): Any? {
        val ctx = launcherContext ?: return null
        return runCatching {
            val lowRes = runCatching {
                XposedHelpers.findClass(BITMAP_INFO, cl).getDeclaredField("LOW_RES_ICON")
                    .apply { isAccessible = true }.get(null) as? Bitmap
            }.getOrNull()
            val tpl = members.firstNotNullOfOrNull { iconOf(it) } ?: return@runCatching null
            if (lowRes != null && tpl === lowRes) return@runCatching null   // members not loaded -> defer
            val s = tpl.width
            val tplSw = if (tpl.config == Bitmap.Config.HARDWARE) tpl.copy(Bitmap.Config.ARGB_8888, false) else tpl
            val rect = opaqueBounds(tplSw) ?: Rect(0, 0, s, s)
            val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            drawFolderComposite(Canvas(out), rect, members)
            val li = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.android.launcher3.icons.LauncherIcons", cl), "obtain", ctx,
            )
            val info = XposedHelpers.callMethod(li, "createIconBitmap", out)
            runCatching { XposedHelpers.callMethod(li, "recycle") }
            recordFolderIcon(info, s, rect)
            info
        }.getOrNull()
    }

    /**
     * Remember which BitmapInfo is our folder's (so the draw probe can find it), and — when the
     * probe is on — log the ACTUAL baked content fraction (opaque bounds of the final icon / its
     * width). That number is what decides the on-screen size together with the draw box `l`: if it
     * drifts first-open vs after-overlay-close, the cause is content fraction; if it is stable but
     * the icon still shrinks, the cause is `l` (see FOLDER/APP draw logs).
     */
    private fun recordFolderIcon(info: Any, srcS: Int, tplRect: Rect) {
        lastFolderBitmapInfoId = System.identityHashCode(info)
        // Always tag our folder BitmapInfos so the scale-fix (probe-independent) can find them.
        synchronized(boundFolderBiIds) {
            if (boundFolderBiIds.size > 64) boundFolderBiIds.clear()
            boundFolderBiIds.add(lastFolderBitmapInfoId)
        }
        if (!probeEnabled) return
        Logx.guard("folder-icon build-log") {
            val icon = XposedHelpers.getObjectField(info, "icon") as? Bitmap
            val w = icon?.width ?: -1
            val sw = icon?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            val baked = sw?.let { opaqueBounds(it) }
            Logx.i(
                "folder-icon build: srcS=$srcS tplRect=${tplRect.width()}x${tplRect.height()} " +
                    "-> iconW=$w baked=${baked?.width()}x${baked?.height()} " +
                    "bakedFrac=${baked?.let { "%.3f".format(it.width().toFloat() / w) }} " +
                    "biId=$lastFolderBitmapInfoId",
            )
        }
    }

    // How many normal-app binds to log after a folder bind, for a same-grid `l` baseline.
    @Volatile private var appBaselineTail = 0
    // BitmapInfo identities actually bound to a folder view, so the draw probe can find the folder's
    // FastBitmapDrawable and read its live scale (the pressed-scale-stuck hypothesis).
    private val boundFolderBiIds = java.util.Collections.synchronizedSet(HashSet<Int>())

    /**
     * Diagnostic (config `foldericonprobe=1`). The draw-time identity match found NOTHING, which
     * means the BitmapInfo actually drawn for the folder is NOT the one we baked — the launcher is
     * swapping our icon after bind (verifyHighRes / IconCache on the sentinel component). So probe
     * the BIND itself:
     *
     *   - hook BubbleTextView.applyFromApplicationInfo(AppInfo); when the AppInfo is our sentinel,
     *     log the BitmapInfo identity actually bound (vs `lastFolderBitmapInfoId` we built), the
     *     raster S, the low-res flag, and getIcon().getBounds() width — which is the icon box `l`
     *     that setIcon() just applied. Log a few following normal-app binds as the baseline `l`.
     *   - also keep an ungated draw probe for the first draws, printing each `l`/`S` and whether it
     *     is our built BitmapInfo, as a cross-check on the on-screen box.
     *
     * Together these say, on the shrink rebind: did the bound BitmapInfo id change (clobber)? did S
     * change? did the icon box `l` change vs neighbours?
     */
    private fun installIconProbe(cl: ClassLoader) {
        probeEnabled = true
        val btv = runCatching { XposedHelpers.findClass("com.android.launcher3.BubbleTextView", cl) }.getOrNull()
        if (btv != null) {
            for (name in arrayOf("applyFromApplicationInfo", "applyFromItemInfoWithIcon")) {
                val n = runCatching {
                    XposedBridge.hookAllMethods(btv, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logx.guard("bind-probe") {
                                val info = param.args?.getOrNull(0) ?: return@guard
                                val comp = runCatching {
                                    XposedHelpers.getObjectField(info, "componentName") as? ComponentName
                                }.getOrNull()
                                val bi = runCatching { XposedHelpers.getObjectField(info, "bitmap") }.getOrNull()
                                val icon = runCatching { XposedHelpers.getObjectField(bi, "icon") as? Bitmap }.getOrNull()
                                val view = param.thisObject as? View
                                val bounds = runCatching {
                                    (XposedHelpers.callMethod(view, "getIcon") as? android.graphics.drawable.Drawable)?.bounds
                                }.getOrNull()
                                if (comp?.packageName == SENTINEL_PKG) {
                                    bi?.let { boundFolderBiIds.add(System.identityHashCode(it)) }
                                    val lowRes = runCatching { XposedHelpers.callMethod(bi, "isLowRes") }.getOrNull()
                                    Logx.i(
                                        "BIND folder: biId=${bi?.let { System.identityHashCode(it) }} " +
                                            "(built=$lastFolderBitmapInfoId) S=${icon?.width} lowRes=$lowRes " +
                                            "frac=${contentFrac(icon)} iconBox=${bounds?.width()}x${bounds?.height()} via=$name",
                                    )
                                    appBaselineTail = 4
                                } else if (appBaselineTail > 0) {
                                    appBaselineTail--
                                    Logx.i("BIND app   : S=${icon?.width} frac=${contentFrac(icon)} iconBox=${bounds?.width()}x${bounds?.height()}")
                                }
                            }
                        }
                    }).size
                }.getOrDefault(0)
                if (n > 0) Logx.i("bind-probe hooked BubbleTextView.$name ($n)")
            }
        } else {
            Logx.e("bind-probe: BubbleTextView not found")
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.launcher3.icons.FastBitmapDrawable", cl, "drawInternal",
                Canvas::class.java, Rect::class.java,
                object : XC_MethodHook() {
                    private var folderLogged = 0
                    private var appLogged = 0
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("draw-probe") {
                            val bi = XposedHelpers.getObjectField(param.thisObject, "mBitmapInfo") ?: return@guard
                            val bmp = XposedHelpers.getObjectField(bi, "icon") as? Bitmap ?: return@guard
                            val rect = param.args[1] as Rect
                            if (rect.width() < 40) return@guard   // skip dots/badges
                            val isFolder = boundFolderBiIds.contains(System.identityHashCode(bi))
                            if (isFolder && folderLogged < 8) {
                                folderLogged++
                                Logx.i("DRAW FOLDER: l=${rect.width()}x${rect.height()} S=${bmp.width} floats={${dumpFloatFields(param.thisObject)}}")
                            } else if (!isFolder && appLogged < 3) {
                                appLogged++
                                Logx.i("DRAW app   : l=${rect.width()}x${rect.height()} S=${bmp.width} floats={${dumpFloatFields(param.thisObject)}}")
                            }
                        }
                    }
                },
            )
            Logx.i("draw-probe installed")
        }.onFailure { Logx.e("draw-probe install failed", it) }
    }

    /** All float fields (name=value) up the class chain — to spot a stuck press/hover scale. */
    private fun dumpFloatFields(obj: Any): String {
        val sb = StringBuilder()
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == java.lang.Float.TYPE) {
                    f.isAccessible = true
                    val v = runCatching { f.getFloat(obj) }.getOrNull()
                    sb.append("${c.simpleName}.${f.name}=$v ")
                }
            }
            c = c.superclass
        }
        return sb.toString().trim()
    }

    /**
     * How many thumbnails a side of the drawer folder's preview holds - 2, 3 or 4.
     *
     * This preview is drawn here, not by the launcher: the drawer folder is a synthetic AppInfo
     * whose icon is a bitmap we composite. So unlike the OPEN folder's grid - which comes from
     * DeviceProfile and is 4x4 on this device with no alternative configured anywhere in the
     * launcher - this number is ours to choose, and worth offering.
     */
    @Volatile private var previewSide = 2

    /** A subtle rounded background + up to [previewSide]^2 member thumbnails, confined to [rect]. */
    private fun drawFolderComposite(canvas: Canvas, rect: Rect, members: List<Any>) {
        val n = previewSide.coerceIn(2, 4)
        val icons = members.mapNotNull { iconOf(it) }.take(n * n)
        if (icons.isEmpty()) return
        val side = minOf(rect.width(), rect.height()).toFloat()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }   // subtle, NOT solid white
        val r = side * 0.28f
        canvas.drawRoundRect(RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), r, r, bgPaint)
        // Padding shrinks as the grid grows, so a 4x4 preview does not spend most of the icon on
        // gaps. At n=2 this is the 0.08 the 2x2 layout always used, so that case is unchanged.
        val pad = side * 0.16f / n
        val cell = (side - pad * (n + 1)) / n
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        icons.forEachIndexed { i, ic ->
            val sw = if (ic.config == Bitmap.Config.HARDWARE) ic.copy(Bitmap.Config.ARGB_8888, false) else ic
            val col = i % n
            val row = i / n
            val left = rect.left + pad + col * (cell + pad)
            val top = rect.top + pad + row * (cell + pad)
            canvas.drawBitmap(sw, null, RectF(left, top, left + cell, top + cell), iconPaint)
        }
    }

    /** Tight opaque (alpha >= thresh) bounding box of a software bitmap, or null if fully clear. */
    private fun opaqueBounds(bmp: Bitmap, aThresh: Int = 16): Rect? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.getOrElse { return null }
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if ((px[base + x] ushr 24) >= aThresh) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** "wFrac×hFrac" — the opaque content's fraction of the raster, the on-screen-size factor. */
    private fun contentFrac(icon: Bitmap?): String {
        if (icon == null) return "?"
        return runCatching {
            val sw = if (icon.config == Bitmap.Config.HARDWARE) icon.copy(Bitmap.Config.ARGB_8888, false) else icon
            val b = opaqueBounds(sw) ?: return "0"
            "%.3fx%.3f".format(b.width().toFloat() / icon.width, b.height().toFloat() / icon.height)
        }.getOrDefault("err")
    }

    // ---------------------------------------------------- 3) click -> overlay

    private fun hookClick(cl: ClassLoader) {
        // 1) Internal drawer: DrawerLauncher -> QuickstepLauncher -> Launcher. The icon tap resolves
        //    (via the vtable) to Launcher.startActivitySafely, so hooking it there diverts a folder tap.
        try {
            val cls = XposedHelpers.findClass(LAUNCHER, cl)
            XposedBridge.hookAllMethods(cls, "startActivitySafely", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("startActivitySafely:folderClick") {
                        if (divertFolderTap(param.args?.getOrNull(2), param.args?.getOrNull(0) as? View, "Launcher")) {
                            // Return a non-null RunnableList; the caller dereferences it.
                            param.setResult(emptyRunnableList(cl))
                        }
                    }
                }
            })
            Logx.i("drawer folder render: hooked $LAUNCHER.startActivitySafely")
        } catch (t: Throwable) {
            Logx.e("drawer folder render: click install failed", t)
        }

        // 2) External drawer: SecondaryDisplayLauncher is a SIBLING of Launcher (both extend
        //    StatefulActivity) and does NOT override startActivitySafely, so its tap resolves to the
        //    ActivityContext interface DEFAULT method — which the Launcher hook above never sees. A
        //    folder tap there fell through to a real launch of our sentinel component, and the system
        //    showed "앱이 설치되지 않았습니다". Hooking the interface default is unreliable (ART dispatches
        //    non-overridden default methods through a per-class copied method that bypasses the hook),
        //    so intercept one step earlier at the drawer's shared icon click listener: both displays'
        //    all-apps grids use ItemClickHandler.INSTANCE (ActivityContext.getItemOnClickListener's
        //    default). Resolve it from the live static field so we depend on no R8 name — the listener
        //    class is synthetic (com.android.launcher3.touch.v), but onClick is pinned by
        //    View.OnClickListener. This is dispatch-agnostic and also covers the internal drawer.
        try {
            val ich = XposedHelpers.findClass("com.android.launcher3.touch.ItemClickHandler", cl)
            val instance: Any = ich.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                ?: throw IllegalStateException("ItemClickHandler.INSTANCE is null")
            XposedBridge.hookAllMethods(instance.javaClass, "onClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("itemClick:folderClick") {
                        val view = param.args?.getOrNull(0) as? View ?: return@guard
                        if (divertFolderTap(view.tag, view, "onClick")) param.setResult(null)   // onClick returns void
                    }
                }
            })
            Logx.i("drawer folder render: hooked ItemClickHandler.INSTANCE.onClick (${instance.javaClass.name})")
        } catch (t: Throwable) {
            Logx.e("drawer folder render: onClick install failed", t)
        }

        // 3) The external TASKBAR all-apps (the reported bug) and the overlay all-apps surfaces are
        //    hosted by ActivityContext implementations that do NOT extend Launcher and do NOT override
        //    startActivitySafely — they inherit the ActivityContext interface DEFAULT. Hooking that
        //    default does NOT help: ART dispatches a non-overridden default through a per-class COPIED
        //    method, so an invoke-interface from these contexts bypasses a hook on the interface method
        //    (verified on device — a hook on ActivityContext.startActivitySafely never fired for
        //    TaskbarActivityContext). Each such context also returns its OWN item click listener
        //    (TaskbarActivityContext -> a private class, not ItemClickHandler.INSTANCE), so hook (2)
        //    misses them too. Intercept at the listener: these contexts DO override the concrete
        //    getItemOnClickListener (directly hookable), and it builds a fresh listener per call, so
        //    wrap its result. The wrapper opens our overlay on a folder tap and delegates everything
        //    else — dispatch-agnostic and independent of the listener's R8 name.
        val listenerContexts = listOf(
            "com.android.launcher3.taskbar.TaskbarActivityContext",
            "com.android.launcher3.taskbar.overlay.TaskbarOverlayContext",
            "com.zui.launcher.taskbar.zuinavrecentallapps.ZuiNavOverlayContext",
        )
        for (name in listenerContexts) {
            val c = XposedHelpers.findClassIfExists(name, cl) ?: continue
            runCatching {
                XposedBridge.hookAllMethods(c, "getItemOnClickListener", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("wrap getItemOnClickListener") {
                            val orig = param.result as? View.OnClickListener ?: return@guard
                            param.setResult(View.OnClickListener { v ->
                                if (!divertFolderTap(v.tag, v, "listener:${name.substringAfterLast('.')}")) {
                                    orig.onClick(v)
                                }
                            })
                        }
                    }
                })
                Logx.i("drawer folder render: wrapped getItemOnClickListener on ${name.substringAfterLast('.')}")
            }.onFailure { Logx.e("drawer folder render: wrap failed for $name", it) }
        }
    }

    /**
     * If [tag] is one of our synthetic folder icons, open its overlay and return true (so the caller
     * consumes the click and no real launch of the sentinel component is attempted). A no-op for any
     * real app/shortcut, so it is safe on every icon click.
     */
    private fun divertFolderTap(tag: Any?, view: View?, src: String): Boolean {
        if (tag == null) return false
        val comp = runCatching { XposedHelpers.getObjectField(tag, "componentName") as? ComponentName }.getOrNull()
            ?: return false
        if (comp.packageName != SENTINEL_PKG) return false
        val groupId = comp.className.removePrefix("g").toLongOrNull()
        if (groupId != null && view != null) {
            openGroup(groupId, view.context)
            Logx.i("drawer folder render: folder click [$src] -> opening group $groupId")
        }
        return true
    }

    private fun openGroup(groupId: Long, viewCtx: Context) = openGroupPlus(groupId, viewCtx, null)

    /** Open a group's overlay, optionally including an [extra] AppInfo just added to it. */
    fun openGroupPlus(groupId: Long, viewCtx: Context, extra: Any?) {
        val cl = classLoader ?: return
        val name = FolderStore.listGroups().firstOrNull { it.id == groupId }?.name ?: "폴더"
        // Read the group's members INSIDE the post, not before it. A caller that just added an app
        // (addAppToFolder) first calls refresh(), whose onAppsUpdated -> transform rebuilds groupMembers
        // on THIS handler before this task runs — so the new app is already present here as a proper
        // AppInfo. Snapshotting before the post (the old code) missed it and spliced in the raw `extra`;
        // for a folder-drag-out that `extra` is a WorkspaceItemInfo (a folder item), which
        // DrawerFolderOverlay.open cannot makeWorkspaceItem() -> it threw and the target folder never
        // opened. Dedup by getTargetComponent (works for AppInfo AND WorkspaceItemInfo, unlike the
        // componentName-field componentOf) so `extra` is only added as a fallback when the rebuilt cache
        // somehow lacks it — and open() now tolerates a WorkspaceItemInfo member either way.
        mainHandler.post {
            val members = ArrayList<Any>(groupMembers[groupId] ?: emptyList())
            if (extra != null && members.none { targetComponentOf(it) == targetComponentOf(extra) }) {
                members.add(extra)
            }
            if (members.isEmpty()) { Logx.i("render: no members for group $groupId"); return@post }
            DrawerFolderOverlay.open(cl, viewCtx, members, name, groupId)
        }
    }

    /** groupId if [componentKey] is one of our synthetic folder icons, else null. */
    fun folderGroupIdOf(componentKey: String): Long? {
        if (!componentKey.startsWith("$SENTINEL_PKG/")) return null
        return componentKey.substringAfter("/").removePrefix("g").toLongOrNull()
    }

    private fun emptyRunnableList(cl: ClassLoader): Any? = runCatching {
        XposedHelpers.findClass("com.android.launcher3.util.RunnableList", cl)
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }.getOrNull()

    // ---------------------------------------------------- helpers

    private fun componentOf(app: Any): String =
        runCatching { (XposedHelpers.getObjectField(app, "componentName") as? ComponentName)?.flattenToShortString() }.getOrNull() ?: ""

    /** Component key via getTargetComponent() — resolves for AppInfo AND WorkspaceItemInfo (a folder
     *  item's tag, whose `componentName` field is null), so it can dedup a folder-drag-out `extra`. */
    private fun targetComponentOf(app: Any): String =
        runCatching { (XposedHelpers.callMethod(app, "getTargetComponent") as? ComponentName)?.flattenToShortString() }.getOrNull()
            ?: componentOf(app)

    private fun iconOf(app: Any): Bitmap? = runCatching {
        val bmpInfo = XposedHelpers.getObjectField(app, "bitmap") ?: return null
        XposedHelpers.getObjectField(bmpInfo, "icon") as? Bitmap
    }.getOrNull()

    /** Open the FolderStore DB once we can reach a launcher context (for post-reboot rendering). */
    private fun ensureStore(alphaListObj: Any) {
        if (storeReady) return
        val ctx = contextFrom(alphaListObj) ?: return
        launcherContext = ctx
        FolderStore.init(ctx)
        FolderStore.cleanup(SENTINEL_PKG)   // repair folders accidentally nested by an earlier build
        // The settings app reaches the folder store only through Control's receiver, and this is
        // the earliest point in the launcher where a real context exists. Registering here rather
        // than waiting for an external taskbar is what lets folders be exported on the tablet alone.
        runCatching { Control.attachContext(ctx) }
        storeReady = true
        Logx.i("drawer folder render: FolderStore ready")
    }

    private fun contextFrom(obj: Any): Context? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                f.isAccessible = true
                val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                when (v) {
                    is Context -> return v
                    is View -> return v.context
                }
            }
            cls = cls.superclass
        }
        return null
    }
}
