package io.laelaps.zuitweaks.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.laelaps.zuitweaks.R
import io.laelaps.zuitweaks.settings.ExtDensityTargets
import io.laelaps.zuitweaks.settings.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

private const val ICON_PX = 96

private fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            val icon = runCatching { it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
                .getOrNull()
            AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString(), icon)
        }
        .sortedBy { it.label.lowercase() }
}

/** Force-stop the given packages, sequentially, as root. Killing them is enough - the fix loads
 *  when each is next started. */
private fun forceStop(packages: List<String>) {
    if (packages.isEmpty()) return
    Root.exec(packages.joinToString("\n") { "am force-stop $it" })
}

/**
 * Picks which apps the external-display tablet fix applies to. Selecting an app writes its module
 * property (root) instantly; it takes effect when that app is next started - no device reboot.
 * ZUI Tweaks, not LSPosed, owns this scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    // Packages toggled this session; they need a restart before the change is visible.
    val pendingRestart = remember { mutableStateListOf<String>() }
    // Serialises the root writes behind toggle(); see there.
    val toggleLock = remember { Mutex() }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val sel = withContext(Dispatchers.IO) { ExtDensityTargets.read(context) }
        selected.addAll(sel)
        withContext(Dispatchers.IO) { ExtDensityTargets.syncAll(context) }
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    fun toggle(pkg: String) {
        val on = pkg !in selected
        if (on) selected.add(pkg) else selected.remove(pkg) // optimistic
        // Only this call's own addition may be rolled back: the package could already have
        // been pending from an earlier toggle, and undoing that would lose a real restart.
        val addedPending = pkg !in pendingRestart
        if (addedPending) pendingRestart.add(pkg)
        scope.launch {
            // Each write is a root shell rewriting the module's property file. Two of them
            // overlapping is a lost update, and tapping down a list is exactly how that
            // happens, so the writes are serialised even though the UI updates immediately.
            toggleLock.withLock {
                val ok = withContext(Dispatchers.IO) { ExtDensityTargets.setSelected(context, pkg, on) }
                if (!ok) {
                    // Root refused: the module never saw the change, so undo the optimistic UI.
                    if (on) selected.remove(pkg) else selected.add(pkg)
                    if (addedPending) pendingRestart.remove(pkg)
                    Toast.makeText(context, R.string.appsel_root_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun restartPending() {
        val list = pendingRestart.toList()
        scope.launch {
            withContext(Dispatchers.IO) { forceStop(list) }
            pendingRestart.clear()
        }
    }

    // Selected apps float to the top (Magisk-style); deselecting drops one straight back into its
    // alphabetical place. derivedStateOf recomputes only when apps/query/selection actually change,
    // and the selection is hoisted to a Set so each comparison is O(1).
    val ordered by remember {
        derivedStateOf {
            val l = apps ?: return@derivedStateOf emptyList<AppInfo>()
            val q = query.trim().lowercase()
            val sel = selected.toHashSet()
            val filtered = if (q.isEmpty()) l
            else l.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            filtered.sortedWith(
                compareByDescending<AppInfo> { it.pkg in sel }.thenBy { it.label.lowercase() },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appsel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {

            if (pendingRestart.isNotEmpty()) {
                val names = pendingRestart.map { pkg ->
                    apps?.firstOrNull { it.pkg == pkg }?.label ?: pkg
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.appsel_restart_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.appsel_restart_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            names.joinToString(", "),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { restartPending() }) {
                            Text(stringResource(R.string.appsel_restart_action))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.appsel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.appsel_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (apps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ordered, key = { it.pkg }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggle(app.pkg) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (app.icon != null) {
                                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            } else {
                                Spacer(Modifier.size(40.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.pkg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Checkbox(checked = app.pkg in selected, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}
