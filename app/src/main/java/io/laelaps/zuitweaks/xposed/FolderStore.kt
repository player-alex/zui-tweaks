package io.laelaps.zuitweaks.xposed

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Persistent store for drawer folders (groups). Framework SQLite in the launcher's own files
 * dir — no AndroidX/Room (this runs in the com.zui.launcher process), so it is safe to load
 * here and survives folder close and reboot.
 *
 * Data model:
 *   groups(id, name, ord)                     — a folder, its label, and its position in the drawer
 *   members(group_id, component, ord)         — apps in a folder, in order; `component` is the
 *                                               stable app key (ComponentName.flattenToShortString)
 *
 * The launcher's own favorites DB is never touched; groups live only here and are rendered into
 * the drawer by our hooks.
 */
object FolderStore {

    private const val DB_NAME = "zuitweaks_folders.db"
    private const val DB_VERSION = 2

    @Volatile private var helper: Helper? = null

    data class Group(val id: Long, val name: String, val order: Int, val members: List<String>)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE groups (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ord INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE members (group_id INTEGER NOT NULL, component TEXT NOT NULL, ord INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(group_id, component))")
            db.execSQL("CREATE INDEX idx_members_component ON members(component)")
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        }
    }

    /** Must be called once (with the launcher context) before any other call. Idempotent. */
    fun init(ctx: Context) {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) {
                    helper = try {
                        Helper(ctx.applicationContext)
                    } catch (t: Throwable) {
                        Logx.e("FolderStore init failed", t); null
                    }
                }
            }
        }
    }

    private fun db(): SQLiteDatabase? = try {
        helper?.writableDatabase
    } catch (t: Throwable) {
        Logx.e("FolderStore db open failed", t); null
    }

    // ---- app key helpers -------------------------------------------------------------------

    fun keyOf(cn: ComponentName): String = cn.flattenToShortString()

    // ---- reads -----------------------------------------------------------------------------

    fun listGroups(): List<Group> {
        val d = db() ?: return emptyList()
        val out = ArrayList<Group>()
        try {
            d.rawQuery("SELECT id, name, ord FROM groups ORDER BY ord, id", null).use { gc ->
                while (gc.moveToNext()) {
                    val id = gc.getLong(0)
                    val members = ArrayList<String>()
                    d.rawQuery("SELECT component FROM members WHERE group_id=? ORDER BY ord, component", arrayOf(id.toString())).use { mc ->
                        while (mc.moveToNext()) members.add(mc.getString(0))
                    }
                    out.add(Group(id, gc.getString(1), gc.getInt(2), members))
                }
            }
        } catch (t: Throwable) {
            Logx.e("FolderStore.listGroups failed", t)
        }
        return out
    }

    /** The persisted drawer slot order (app component keys and folder sentinel components). */
    fun drawerOrder(): List<String> {
        val d = db() ?: return emptyList()
        return try {
            d.rawQuery("SELECT value FROM meta WHERE key='drawer_order'", null).use {
                if (it.moveToNext()) it.getString(0)?.split('\n')?.filter { s -> s.isNotEmpty() } ?: emptyList()
                else emptyList()
            }
        } catch (t: Throwable) {
            Logx.e("FolderStore.drawerOrder failed", t); emptyList()
        }
    }

    /** Replace the persisted drawer slot order. */
    fun setDrawerOrder(slots: List<String>) {
        val d = db() ?: return
        runCatching {
            d.insertWithOnConflict("meta", null, ContentValues().apply {
                put("key", "drawer_order"); put("value", slots.joinToString("\n"))
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }.onFailure { Logx.e("FolderStore.setDrawerOrder failed", it) }
    }

    /** True once the user has manually reordered the drawer (stops the default natural-order mirror). */
    fun drawerOrderCustomized(): Boolean {
        val d = db() ?: return false
        return try {
            d.rawQuery("SELECT value FROM meta WHERE key='drawer_customized'", null).use {
                it.moveToNext() && it.getString(0) == "1"
            }
        } catch (t: Throwable) { false }
    }

    fun setDrawerOrderCustomized() {
        val d = db() ?: return
        runCatching {
            d.insertWithOnConflict("meta", null, ContentValues().apply {
                put("key", "drawer_customized"); put("value", "1")
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** The group id that contains [component], or null. */
    fun groupIdFor(component: String): Long? {
        val d = db() ?: return null
        return try {
            d.rawQuery("SELECT group_id FROM members WHERE component=? LIMIT 1", arrayOf(component)).use {
                if (it.moveToNext()) it.getLong(0) else null
            }
        } catch (t: Throwable) {
            Logx.e("FolderStore.groupIdFor failed", t); null
        }
    }

    // ---- writes ----------------------------------------------------------------------------

    /** Create a group with [name] and the given ordered [components]; returns its id (or -1). */
    fun createGroup(name: String, components: List<String>): Long {
        val d = db() ?: return -1
        return try {
            d.beginTransaction()
            val nextOrd = nextGroupOrder(d)
            val gid = d.insert("groups", null, ContentValues().apply {
                put("name", name); put("ord", nextOrd)
            })
            if (gid >= 0) writeMembers(d, gid, components)
            d.setTransactionSuccessful()
            Logx.i("FolderStore: created group $gid '$name' with ${components.size} members")
            gid
        } catch (t: Throwable) {
            Logx.e("FolderStore.createGroup failed", t); -1
        } finally {
            runCatching { d.endTransaction() }
        }
    }

    /** Replace a group's members with the given ordered list (also used to persist a reorder). */
    fun setMembers(groupId: Long, orderedComponents: List<String>) {
        val d = db() ?: return
        try {
            d.beginTransaction()
            d.delete("members", "group_id=?", arrayOf(groupId.toString()))
            writeMembers(d, groupId, orderedComponents)
            d.setTransactionSuccessful()
        } catch (t: Throwable) {
            Logx.e("FolderStore.setMembers failed", t)
        } finally {
            runCatching { d.endTransaction() }
        }
    }

    fun addMember(groupId: Long, component: String) {
        val d = db() ?: return
        try {
            val ord = nextMemberOrder(d, groupId)
            d.insertWithOnConflict("members", null, ContentValues().apply {
                put("group_id", groupId); put("component", component); put("ord", ord)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        } catch (t: Throwable) {
            Logx.e("FolderStore.addMember failed", t)
        }
    }

    fun removeMember(groupId: Long, component: String) {
        val d = db() ?: return
        runCatching { d.delete("members", "group_id=? AND component=?", arrayOf(groupId.toString(), component)) }
        // Drop the group if it now has fewer than 2 members (an empty/singleton folder is pointless).
        val dropped = runCatching {
            d.rawQuery("SELECT COUNT(*) FROM members WHERE group_id=?", arrayOf(groupId.toString())).use {
                if (it.moveToNext() && it.getInt(0) < 2) { deleteGroup(groupId); true } else false
            }
        }.getOrDefault(false)
        // Re-number the survivors 0,1,2,… so a removal never leaves an ord gap (kept the list ordered
        // but sparse — 0,2,3 after removing ord 1 — which is harmless but drifts and is easy to misread).
        if (!dropped) compactMemberOrds(d, groupId)
    }

    /** Renumber a group's members to a contiguous 0,1,2,… in their current order. */
    private fun compactMemberOrds(d: SQLiteDatabase, groupId: Long) {
        val comps = ArrayList<String>()
        runCatching {
            d.rawQuery("SELECT component FROM members WHERE group_id=? ORDER BY ord, component", arrayOf(groupId.toString())).use {
                while (it.moveToNext()) comps.add(it.getString(0))
            }
        }
        if (comps.isEmpty()) return
        try {
            d.beginTransaction()
            comps.forEachIndexed { i, c ->
                d.update("members", ContentValues().apply { put("ord", i) }, "group_id=? AND component=?",
                    arrayOf(groupId.toString(), c))
            }
            d.setTransactionSuccessful()
        } catch (t: Throwable) {
            Logx.e("FolderStore.compactMemberOrds failed", t)
        } finally {
            runCatching { d.endTransaction() }
        }
    }

    fun renameGroup(groupId: Long, name: String) {
        val d = db() ?: return
        runCatching {
            d.update("groups", ContentValues().apply { put("name", name) }, "id=?", arrayOf(groupId.toString()))
        }
    }

    fun deleteGroup(groupId: Long) {
        val d = db() ?: return
        runCatching {
            d.delete("members", "group_id=?", arrayOf(groupId.toString()))
            d.delete("groups", "id=?", arrayOf(groupId.toString()))
        }
    }

    /**
     * Repair: drop any member that is actually a folder sentinel (a group must only hold real apps),
     * then delete groups left with fewer than 2 members. Fixes folders accidentally nested by an
     * earlier build. Call once after init.
     */
    fun cleanup(sentinelPkg: String) {
        val d = db() ?: return
        runCatching { d.delete("members", "component LIKE ?", arrayOf("$sentinelPkg/%")) }
        runCatching {
            val small = ArrayList<Long>()
            d.rawQuery(
                "SELECT g.id FROM groups g LEFT JOIN members m ON m.group_id=g.id GROUP BY g.id HAVING COUNT(m.component) < 2",
                null,
            ).use { while (it.moveToNext()) small.add(it.getLong(0)) }
            small.forEach { deleteGroup(it) }
            if (small.isNotEmpty()) Logx.i("FolderStore.cleanup: removed ${small.size} invalid group(s)")
        }
    }

    // ---- export / import -------------------------------------------------------------------

    /**
     * The whole grouping state as `key=value` lines, to be appended to the settings file the
     * app exports. Deliberately the same shape as the settings, so one file carries both and
     * SettingsStore.importText simply skips these keys (they are not in the Schema).
     *
     * Fields inside a value are tab-separated. A component key
     * (ComponentName.flattenToShortString) can never contain a tab; a folder name is typed by
     * the user, so it is sanitised on the way out rather than trusted on the way in.
     *
     * This runs in the launcher process - the app has no access to this database - and reaches
     * the app through Control's `folders:export` command.
     */
    fun exportText(): String = buildString {
        appendLine("# --- drawer folders ---")
        appendLine("# Written and read by the launcher hooks, not by the settings app.")
        for (g in listGroups()) {
            if (g.members.isEmpty()) continue
            appendLine(KEY_GROUP + "=" + (listOf(clean(g.name)) + g.members).joinToString("\t"))
        }
        val order = drawerOrder()
        if (order.isNotEmpty()) appendLine(KEY_ORDER + "=" + order.joinToString("\t"))
        if (drawerOrderCustomized()) appendLine(KEY_CUSTOMIZED + "=1")
    }

    /**
     * Replaces the grouping state with what [text] describes; returns how many groups were
     * restored, or -1 if the text carried no folder lines at all.
     *
     * Replace rather than merge: an import is "make this device look like that one", and
     * merging would leave folders the file does not mention, which is not what a restore means.
     * -1 (rather than 0) for "nothing to import" so the caller can tell a settings-only file
     * apart from a file that genuinely describes zero folders.
     */
    fun importText(text: String): Int {
        val groups = ArrayList<List<String>>()
        var order: List<String>? = null
        var customized = false
        var sawAny = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (!line.contains('=')) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            when (key) {
                KEY_GROUP -> {
                    sawAny = true
                    val parts = value.split('\t').filter { it.isNotEmpty() }
                    // name + at least two members; a one-app folder is what cleanup() deletes.
                    if (parts.size >= 3) groups.add(parts)
                }
                KEY_ORDER -> { sawAny = true; order = value.split('\t').filter { it.isNotEmpty() } }
                KEY_CUSTOMIZED -> { sawAny = true; customized = value == "1" }
            }
        }
        if (!sawAny) return -1

        val d = db() ?: return -1
        return try {
            d.beginTransaction()
            d.delete("members", null, null)
            d.delete("groups", null, null)
            var n = 0
            for (parts in groups) {
                val gid = d.insert("groups", null, ContentValues().apply {
                    put("name", parts[0]); put("ord", n)
                })
                if (gid >= 0) { writeMembers(d, gid, parts.drop(1)); n++ }
            }
            d.setTransactionSuccessful()
            Logx.i("FolderStore.importText: restored $n group(s)")
            n
        } catch (t: Throwable) {
            Logx.e("FolderStore.importText failed", t); -1
        } finally {
            runCatching { d.endTransaction() }
            // Outside the transaction: these are separate meta rows and a failure to write the
            // order must not roll back the groups, which are the part that matters.
            order?.let { setDrawerOrder(it) }
            if (customized) setDrawerOrderCustomized()
        }
    }

    private const val KEY_GROUP = "folder.group"
    private const val KEY_ORDER = "folder.order"
    private const val KEY_CUSTOMIZED = "folder.customized"

    /** Tabs and newlines are the record separators; a folder name may not carry either. */
    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').trim()

    // ---- internals -------------------------------------------------------------------------

    private fun writeMembers(d: SQLiteDatabase, groupId: Long, components: List<String>) {
        components.forEachIndexed { i, c ->
            d.insertWithOnConflict("members", null, ContentValues().apply {
                put("group_id", groupId); put("component", c); put("ord", i)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun nextGroupOrder(d: SQLiteDatabase): Int =
        d.rawQuery("SELECT COALESCE(MAX(ord),-1)+1 FROM groups", null).use { if (it.moveToNext()) it.getInt(0) else 0 }

    private fun nextMemberOrder(d: SQLiteDatabase, groupId: Long): Int =
        d.rawQuery("SELECT COALESCE(MAX(ord),-1)+1 FROM members WHERE group_id=?", arrayOf(groupId.toString())).use {
            if (it.moveToNext()) it.getInt(0) else 0
        }
}
