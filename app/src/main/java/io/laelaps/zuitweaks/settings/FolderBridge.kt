package io.laelaps.zuitweaks.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * The settings app's only way to reach the drawer-folder state.
 *
 * Grouping lives in a SQLite database inside **com.zui.launcher**'s data directory, written by
 * the hooks that render the drawer. SELinux keeps one app out of another's data, so this app
 * cannot open it - not to export it, not to restore it. What it can do is ask the launcher,
 * because our own [io.laelaps.zuitweaks.xposed.Control] receiver is registered in that process.
 *
 * So both directions are an **ordered** broadcast: ordered because that is the only broadcast
 * whose receiver can answer, through `setResultData`. If nothing answers within [TIMEOUT_MS] the
 * result is null, which is a real state rather than an error - it means the module is not active
 * in LSPosed, or the launcher has not rendered its drawer since it started, so there is no
 * receiver on the other end. The caller reports that instead of writing a file that silently
 * claims the device has no folders.
 */
object FolderBridge {

    /**
     * Generous on purpose. The reply is a couple of SQLite reads, but the launcher may be cold,
     * and an export that gives up early would write a settings file missing its folders - the
     * exact failure this exists to prevent. A user waiting a moment for Export is fine.
     */
    private const val TIMEOUT_MS = 4000L

    /** The folder lines to append to a settings export, or null when nothing answered. */
    suspend fun export(context: Context): String? =
        request(context, Channels.CMD_FOLDERS_EXPORT, null)

    /**
     * Hands [text] (a whole settings file is fine - the launcher picks out the lines it owns)
     * to the launcher. Returns the number of folders restored, null when nothing answered, and
     * -1 when the file carried no folder lines at all.
     */
    suspend fun import(context: Context, text: String): Int? =
        request(context, Channels.CMD_FOLDERS_IMPORT, text)?.trim()?.toIntOrNull()

    private suspend fun request(context: Context, command: String, data: String?): String? = try {
        withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val intent = Intent(Channels.CONTROL_ACTION)
                    // Pinned to the launcher so this is not offered to every receiver on the
                    // device - the same reason sendReload() pins it.
                    .setPackage(Channels.TARGET_PKG)
                    .putExtra(Channels.EXTRA_METHOD, command)
                if (data != null) intent.putExtra(Channels.EXTRA_DATA, data)
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: Intent?) {
                            if (cont.isActive) cont.resume(resultData)
                        }
                    },
                    null,
                    0,
                    null,
                    Bundle(),
                )
            }
        }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (_: Throwable) {
        null
    }
}
