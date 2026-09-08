package io.laelaps.zuitweaks.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.laelaps.zuitweaks.R
import io.laelaps.zuitweaks.settings.Channels
import io.laelaps.zuitweaks.settings.FolderBridge
import io.laelaps.zuitweaks.settings.FreeformSidebar
import io.laelaps.zuitweaks.settings.Group
import io.laelaps.zuitweaks.settings.KeyboardModule
import io.laelaps.zuitweaks.settings.Labels
import io.laelaps.zuitweaks.settings.NumberSetting
import io.laelaps.zuitweaks.settings.Proc
import io.laelaps.zuitweaks.settings.Root
import io.laelaps.zuitweaks.settings.Schema
import io.laelaps.zuitweaks.settings.Setting
import io.laelaps.zuitweaks.settings.SettingsStore
import io.laelaps.zuitweaks.settings.Toggle
import io.laelaps.zuitweaks.settings.ZygiskModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which control owns the privileged action that is running. Only one runs at a time; the point
 * of naming the owner is that everything else keeps showing its own state instead of mirroring
 * someone else's - press Install on one module card and the other used to spin too.
 *
 * [Restart] carries the process, because the pending-changes card can offer several restart
 * buttons at once and "which one is running" is exactly what a shared flag cannot say.
 */
private sealed interface ActionOwner {
    data object Keyboard : ActionOwner
    data object ExtDensity : ActionOwner
    data class Restart(val proc: Proc) : ActionOwner
    data object Sidebar : ActionOwner
}


/**
 * The whole settings surface.
 *
 * Two things this screen refuses to pretend about, because both cost time to rediscover:
 *
 *  - a toggle here does nothing unless LSPosed actually bridged our preferences into the
 *    hooked process, so the module status is stated up front rather than assumed;
 *  - a hook is injected only when a process starts, so a changed setting is pending until
 *    that process restarts. The screen names which one and restarts it.
 *
 * The device is rooted by definition here - Magisk and LSPosed are prerequisites - so the
 * screen does the privileged work itself through [Root] instead of printing `adb shell`
 * lines for someone to paste. Setting this module up needs no terminal at all: the
 * key-layout Magisk module is installed from this screen, its real mount state is read
 * back here, and the processes that have to restart are restarted here. The adb commands
 * remain as a fallback for a device where root is refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onOpenAppSelection: () -> Unit = {}) {

    val context = LocalContext.current

    // Bumped on every write; the reads below are keyed to it so the list recomposes.
    var revision by remember { mutableIntStateOf(0) }
    val pending = remember { mutableStateListOf<Proc>() }

    val scope = rememberCoroutineScope()
    // null while the first probe is in flight - "unknown" and "broken" must not look alike.
    var moduleStatus by remember { mutableStateOf<KeyboardModule.Status?>(null) }
    var extModStatus by remember { mutableStateOf<ZygiskModule.Status?>(null) }
    var sidebarStatus by remember { mutableStateOf<FreeformSidebar.Status?>(null) }
    // Which card started the privileged action that is running, and which card the shell
    // output on screen belongs to. These used to be one screen-wide `busy` flag and one
    // output string, so pressing Install on either module card put BOTH cards into their
    // progress state and printed the same shell transcript under both - two installs
    // appeared to be running. Only ever one action at a time; what changed is that the
    // other card no longer claims it.
    var runningFor by remember { mutableStateOf<ActionOwner?>(null) }
    var outputFor by remember { mutableStateOf<ActionOwner?>(null) }
    var lastOutput by remember { mutableStateOf("") }
    val busy = runningFor != null

    fun probe() {
        scope.launch {
            moduleStatus = withContext(Dispatchers.IO) { KeyboardModule.status() }
            extModStatus = withContext(Dispatchers.IO) { ZygiskModule.status() }
            sidebarStatus = withContext(Dispatchers.IO) { FreeformSidebar.status(context) }
        }
    }

    /**
     * Run a privileged action, then re-read both module states rather than assuming it worked.
     *
     * [onDone] runs once the action has actually finished, with its result. It is the only
     * safe place for follow-up state: this function returns immediately - and returns without
     * doing anything at all while another action is in flight - so anything written straight
     * after the call would be applied whether or not the action ran, let alone succeeded.
     */
    fun rootAction(
        owner: ActionOwner,
        onDone: (Root.Result) -> Unit = {},
        action: () -> Root.Result,
    ) {
        if (runningFor != null) return
        runningFor = owner
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            lastOutput = result.output
            outputFor = owner
            moduleStatus = withContext(Dispatchers.IO) { KeyboardModule.status() }
            extModStatus = withContext(Dispatchers.IO) { ZygiskModule.status() }
            sidebarStatus = withContext(Dispatchers.IO) { FreeformSidebar.status(context) }
            runningFor = null
            onDone(result)
        }
    }

    // One probe per screen, which is also where Magisk's grant prompt appears.
    LaunchedEffect(Unit) { probe() }

    /**
     * The keyboard module's card. It used to move: pinned to the top while the module wanted
     * something, folded into the Keyboard section once it was ACTIVE, to keep a second
     * full-width banner from living above the list forever. It now sits in the Modules block
     * at the top with the other one instead - a card that moves is worse than a card that
     * stays, when what the user is looking for is "where do I install this".
     */
    val keyboardCard: @Composable () -> Unit = {
        KeyboardModuleCard(
            status = moduleStatus,
            busy = runningFor == ActionOwner.Keyboard,
            output = if (outputFor == ActionOwner.Keyboard) lastOutput else "",
            onInstall = { rootAction(ActionOwner.Keyboard) { KeyboardModule.install(context) } },
            onReboot = { rootAction(ActionOwner.Keyboard) { KeyboardModule.reboot() } },
            onClearMarkers = { rootAction(ActionOwner.Keyboard) { KeyboardModule.clearMarkers() } },
            onRetry = { Root.forget(); probe() },
        )
    }

    // Both of these stat and read files on /sdcard, so they are done off the main thread and
    // the screen simply shows nothing extra until the answer arrives. Keyed to revision, so
    // a write re-reads them exactly as the synchronous version did.
    val overrides by produceState(initialValue = emptyMap<String, String>(), revision) {
        value = withContext(Dispatchers.IO) { Channels.debugOverrides(context) }
    }
    // exists() returning true is trustworthy; false is not, because this app cannot read
    // /sdcard without storage permission. So the banner only ever appears on a positive.
    val killed by produceState(initialValue = false, revision) {
        value = withContext(Dispatchers.IO) { Channels.killSwitchPresent() }
    }

    fun touched(setting: Setting) {
        revision++
        if (!setting.live) setting.procs.forEach { if (it !in pending) pending.add(it) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { inner ->
        // The list is capped and centred. Full-bleed text across a 3040px panel is
        // unreadable no matter how short the sentence is.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 32.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            item {
                if (killed) {
                    NoticeCard(
                        title = stringResource(R.string.status_killswitch_title),
                        body = stringResource(R.string.status_killswitch_body),
                        tone = Tone.WARN,
                        leading = { StatusBadge(ok = false) },
                    )
                } else if (store.lsposedActive) {
                    // Working: just the badge + "Active", no explanatory body.
                    NoticeCard(
                        title = stringResource(R.string.status_active_title),
                        body = "",
                        tone = Tone.OK,
                        leading = { StatusBadge(ok = true) },
                    )
                } else {
                    NoticeCard(
                        title = stringResource(R.string.status_inactive_title),
                        body = stringResource(R.string.status_inactive_body),
                        tone = Tone.WARN,
                        leading = { StatusBadge(ok = false) },
                    )
                }
            }

            // Not a module - a one-off helper for getting past LSPosed's dialogs - so it sits
            // above the Modules block rather than inside it. Shown while the sidebar is ON (the
            // state that blocks those dialogs) and while THIS app has it off, so it is never
            // quietly left that way; hidden when the user turned it off himself, because then
            // there is nothing to offer and nothing to put back.
            sidebarStatus?.let { sb ->
                val ours = sb.state == FreeformSidebar.State.OFF && sb.suppressedByUs
                if (sb.state == FreeformSidebar.State.ON || ours) {
                    item(key = "sidebar") {
                        val tone = if (ours) Tone.WARN else Tone.INFO
                        val (container, onContainer) = toneColors(tone)
                        NoticeCard(
                            title = stringResource(R.string.sidebar_title),
                            body = stringResource(
                                if (ours) R.string.sidebar_body_off else R.string.sidebar_body_on,
                            ),
                            tone = tone,
                        ) {
                            Spacer(Modifier.height(10.dp))
                            if (runningFor == ActionOwner.Sidebar) {
                                Progress()
                            } else {
                                // Same Row + tone-matched Button as the module cards, so the
                                // action sits on the card's left edge exactly like theirs.
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !busy,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = onContainer,
                                            contentColor = container,
                                        ),
                                        onClick = {
                                            rootAction(ActionOwner.Sidebar) {
                                                if (ours) FreeformSidebar.restore(context)
                                                else FreeformSidebar.suppress(context)
                                            }
                                        },
                                    ) {
                                        Text(
                                            stringResource(
                                                if (ours) R.string.action_sidebar_on
                                                else R.string.action_sidebar_off,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Both Magisk modules are installed from here. They used to live in the sections
            // their settings belong to - Keyboard and Misc - which left two prerequisites at
            // opposite ends of a long scroll, and setting the device up is one job. The
            // external-display *fix* stays in Misc: that card is the app picker, not an install.
            item(key = "modules-h") {
                Text(
                    text = stringResource(R.string.section_modules),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
                )
            }
            item(key = "keymod") { keyboardCard() }
            item(key = "extmod") {
                ExtDensityModuleCard(
                    status = extModStatus,
                    busy = runningFor == ActionOwner.ExtDensity,
                    output = if (outputFor == ActionOwner.ExtDensity) lastOutput else "",
                    onInstall = { rootAction(ActionOwner.ExtDensity) { ZygiskModule.install(context) } },
                    onReboot = { rootAction(ActionOwner.ExtDensity) { ZygiskModule.reboot() } },
                    onEnable = { rootAction(ActionOwner.ExtDensity) { ZygiskModule.enable() } },
                )
            }

            if (overrides.isNotEmpty()) {
                item {
                    NoticeCard(
                        title = stringResource(R.string.status_override_title),
                        body = stringResource(R.string.status_override_body),
                        tone = Tone.WARN,
                    ) {
                        Mono(overrides.entries.joinToString("\n") { "${it.key} = ${it.value}" })
                    }
                }
            }

            if (pending.isNotEmpty()) {
                item {
                    NoticeCard(
                        title = stringResource(R.string.restart_title),
                        body = stringResource(R.string.restart_body),
                        tone = Tone.INFO,
                    ) {
                        val rooted = moduleStatus?.state != KeyboardModule.State.NO_ROOT
                        pending.forEach { proc ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(Labels.of(proc)),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // The spinner belongs to the process actually restarting. The
                                // others are only disabled - one privileged action at a time -
                                // rather than all of them claiming to be running.
                                if (runningFor == ActionOwner.Restart(proc)) {
                                    Progress()
                                } else {
                                    Button(
                                        enabled = !busy,
                                        onClick = {
                                            rootAction(
                                                ActionOwner.Restart(proc),
                                                onDone = { if (it.ok) pending.remove(proc) },
                                            ) { Root.exec(proc.restartScript) }
                                        },
                                    ) { Text(stringResource(R.string.action_restart_now)) }
                                    TextButton(onClick = { copy(context, proc.adbCmd) }) {
                                        Text(stringResource(R.string.action_copy))
                                    }
                                }
                            }
                            // Only worth showing when the button above cannot work.
                            if (!rooted) Mono(proc.adbCmd)
                        }
                    }
                }
            }

            Schema.byGroup.forEach { (group, settings) ->
                if (settings.isEmpty()) return@forEach
                item(key = "h-${group.name}") {
                    Text(
                        text = stringResource(Labels.of(group)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
                    )
                }
                if (group == Group.MISC) item(key = "misc-extfix") { ExtFixCard(onOpenAppSelection) }
                items(settings, key = { it.key }) { setting ->
                    SettingCard(
                        setting = setting,
                        store = store,
                        revision = revision,
                        // A schema setting is owned by these preferences; no file can shadow it.
                        shadowed = false,
                        onChanged = { touched(setting) },
                    )
                }
            }


            item {
                Spacer(Modifier.height(16.dp))
                ImportExportCard(
                    store = store,
                    onImported = {
                        revision++
                        Schema.all.filterNot { it.live }
                            .flatMap { it.procs }
                            .distinct()
                            .forEach { if (it !in pending) pending.add(it) }
                    },
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            sendReload(context)
                            revision++
                        },
                    ) { Text(stringResource(R.string.action_reload)) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            store.resetAll()
                            revision++
                            Schema.all.filterNot { it.live }
                                .flatMap { it.procs }
                                .distinct()
                                .forEach { if (it !in pending) pending.add(it) }
                        },
                    ) { Text(stringResource(R.string.action_reset)) }
                }
            }
        }
        }
    }
}

/** Install / status of the external-display fix Zygisk module, in-app (like the keyboard module). */
@Composable
private fun ExtDensityModuleCard(
    status: ZygiskModule.Status?,
    busy: Boolean,
    output: String,
    onInstall: () -> Unit,
    onReboot: () -> Unit,
    onEnable: () -> Unit,
) {
    if (status == null) {
        NoticeCard(
            title = stringResource(R.string.extmod_title),
            body = stringResource(R.string.extmod_checking),
            tone = Tone.INFO,
        ) {
            Spacer(Modifier.height(8.dp))
            Progress()
        }
        return
    }
    val tone = when (status.state) {
        ZygiskModule.State.ACTIVE -> Tone.OK
        ZygiskModule.State.NOT_INSTALLED, ZygiskModule.State.PENDING_REBOOT -> Tone.INFO
        else -> Tone.WARN
    }
    val body = when (status.state) {
        ZygiskModule.State.NO_ROOT -> R.string.extmod_noroot
        ZygiskModule.State.NO_MAGISK -> R.string.extmod_nomagisk
        ZygiskModule.State.NOT_INSTALLED -> R.string.extmod_notinstalled
        ZygiskModule.State.PENDING_REBOOT -> R.string.extmod_pending
        ZygiskModule.State.DISABLED -> R.string.extmod_disabled
        ZygiskModule.State.ACTIVE -> R.string.extmod_active
    }
    val leading: (@Composable () -> Unit)? =
        if (status.state == ZygiskModule.State.ACTIVE) {
            { StatusBadge(ok = true) }
        } else {
            null
        }

    NoticeCard(
        title = stringResource(R.string.extmod_title),
        body = stringResource(body),
        tone = tone,
        leading = leading,
    ) {
        Spacer(Modifier.height(10.dp))
        if (busy) {
            Progress()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status.state) {
                    ZygiskModule.State.NOT_INSTALLED ->
                        Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) }

                    ZygiskModule.State.PENDING_REBOOT ->
                        Button(onClick = onReboot) { Text(stringResource(R.string.action_reboot)) }

                    ZygiskModule.State.DISABLED ->
                        Button(onClick = onEnable) { Text(stringResource(R.string.action_enable)) }

                    ZygiskModule.State.ACTIVE ->
                        TextButton(
                            onClick = onInstall,
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) { Text(stringResource(R.string.action_reinstall)) }

                    ZygiskModule.State.NO_ROOT, ZygiskModule.State.NO_MAGISK -> Unit
                }
            }
        }
        if (output.isNotEmpty()) Mono(output.take(600))
    }
}

/**
 * Moves the settings as a file, and only when asked.
 *
 * The module used to take its configuration from `/sdcard/zuitweaks.conf` at hook-install time,
 * which put a feature at the mercy of whether one process could read external storage at that
 * moment - at boot the launcher cannot, so the drawer-folder hooks silently never installed
 * (measured 2026-09-04). Settings live in the app now. A file is still the right way to carry
 * them to another device or keep a backup, so it is an explicit action with a document picker,
 * needing no storage permission, and nothing reads it behind the user's back.
 */
@Composable
private fun ImportExportCard(store: SettingsStore, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // NOT "text/plain": the picker rewrites the name to match the MIME type. AOSP's
    // FileUtils.splitFileName maps an unknown extension (".conf") to application/octet-stream,
    // so asking for that type makes the requested and derived types agree and the name is kept.
    // With text/plain they disagree and the platform appends its own extension - the file
    // landed as "zuitweaks-settings.conf.txt".
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Folders come from the launcher process, not from here - see FolderBridge. Asked
            // for first, because a null answer changes what the toast is allowed to claim.
            val folders = FolderBridge.export(context)
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val text = store.exportText() + folders.orEmpty()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: return@runCatching -1
                    text.lineSequence().count { it.contains('=') && !it.startsWith("folder.") }
                }.getOrDefault(-1)
            }
            val message = when {
                written < 0 -> context.getString(R.string.impex_failed)
                folders == null -> context.getString(R.string.export_done_nofolders, written)
                else -> context.getString(
                    R.string.export_done_folders,
                    written,
                    folders.lineSequence().count { it.startsWith("folder.group=") },
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val count = if (read == null) -1 else store.importText(read)
            // The whole file goes over; the launcher picks out the lines it owns. Only attempted
            // when the file actually parsed, so a failed read cannot wipe the folders.
            val folders = if (read == null) null else FolderBridge.import(context, read)
            if (count >= 0) onImported()
            val message = when {
                count < 0 -> context.getString(R.string.impex_failed)
                folders != null && folders >= 0 ->
                    context.getString(R.string.import_done_folders, count, folders)
                else -> context.getString(R.string.import_done, count)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.export_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("zuitweaks-settings.conf") }) {
                    Text(stringResource(R.string.action_export))
                }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.action_import))
                }
            }
        }
    }
}

/** The external-display fix entry in the Misc section: a description and a button into the picker. */
@Composable
private fun ExtFixCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.extfix_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.extfix_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpen) { Text(stringResource(R.string.extfix_select)) }
        }
    }
}

@Composable
private fun SettingCard(
    setting: Setting,
    store: SettingsStore,
    revision: Int,
    shadowed: Boolean,
    onChanged: () -> Unit,
) {
    val labels = Labels.of(setting)
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            // A shadowed setting dims its text rather than fading the whole card towards
            // the background, which used to leave it looking half-rendered.
            containerColor = if (shadowed) scheme.surfaceContainerLow else scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface.copy(alpha = if (shadowed) 0.5f else 1f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(labels.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Badges(setting, store, revision)
                }
                if (setting is Toggle) {
                    Spacer(Modifier.width(12.dp))
                    val checked = remember(revision) { store.bool(setting) }
                    Switch(
                        checked = checked,
                        enabled = !shadowed,
                        onCheckedChange = {
                            store.set(setting, it)
                            onChanged()
                        },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(labels.summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (setting is NumberSetting) {
                val current = remember(revision) { store.int(setting) }
                // Live position, so the thumb tracks the drag; committed on release only,
                // to avoid a preferences write per pixel.
                var live by remember(revision) { mutableIntStateOf(current) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$live${stringResource(labels.unitRes)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(72.dp),
                    )
                    Slider(
                        value = live.toFloat(),
                        valueRange = setting.min.toFloat()..setting.max.toFloat(),
                        // No tick marks. onValueChange still snaps to the step grid, so
                        // nothing is lost - but `steps` also draws a dot per position, and
                        // 0..5000 by 100 is forty-nine dots under the thumb.
                        steps = 0,
                        enabled = !shadowed,
                        onValueChange = { live = setting.clamp(it.toInt()) },
                        onValueChangeFinished = {
                            store.set(setting, live)
                            onChanged()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Only one badge is allowed to be coloured: the one that means something is still owed.
 * Four tinted chips under every title was most of what made the screen loud, and
 * "Applies at once" is not news - it is what a setting is supposed to do.
 */
@Composable
private fun Badges(setting: Setting, store: SettingsStore, revision: Int) {
    val isDefault = remember(revision) { store.isDefault(setting) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!setting.live) Badge(stringResource(R.string.badge_restart), accent = true)
        setting.procs.forEach { Badge(stringResource(Labels.of(it))) }
        if (isDefault) Badge(stringResource(R.string.badge_default))
    }
}

@Composable
private fun Badge(text: String, accent: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (accent) scheme.tertiaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (accent) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

private enum class Tone { OK, WARN, INFO }

/**
 * Three states, three Material container roles.
 *
 * These used to be an accent composited over the background at 10% alpha, which is why
 * the colours never matched: the result depends on whatever is behind it, and the paired
 * text colour was that accent at full strength, so contrast varied card by card. A
 * container role ships its own on-colour and everything inside inherits it.
 */
/** Container colour and its on-colour, for one tone. */
@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        Tone.OK -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.WARN -> scheme.errorContainer to scheme.onErrorContainer
        Tone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    tone: Tone,
    leading: (@Composable () -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val (container, onContainer) = toneColors(tone)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        // The leading badge sits next to the title only; the body and actions stay at the card's
        // left edge so a badge never indents the buttons underneath.
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            extra?.invoke(this)
        }
    }
}

/** A small round status badge: a filled circle with a glyph. Green check = working, red X =
 *  a problem. Drawn without an icon dependency, readable in light and dark. */
@Composable
private fun StatusBadge(ok: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (ok) "✓" else "✕",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Monospaced text with no surface of its own. It appears inside three differently
 * coloured cards, and any fixed background it carried clashed with at least one.
 */
@Composable
private fun Mono(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = LocalContentColor.current.copy(alpha = 0.75f),
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * State of the Magisk key-layout module, and every action needed to fix it.
 *
 * The states are not cosmetic. `NOT_LOADED` exists because a module can be installed,
 * enabled and byte-correct while Magisk has not mounted it - that is a real fault this
 * project shipped on 2026-08-24, and nothing in the Magisk app distinguishes it from a
 * working install. It is read from Magisk's own boot log, and confirmed against the
 * layouts actually in /system, because the module directory looks identical either way.
 */
@Composable
private fun KeyboardModuleCard(
    status: KeyboardModule.Status?,
    busy: Boolean,
    output: String,
    onInstall: () -> Unit,
    onReboot: () -> Unit,
    onClearMarkers: () -> Unit,
    onRetry: () -> Unit,
) {
    if (status == null) {
        NoticeCard(
            title = stringResource(R.string.keymod_title),
            body = stringResource(R.string.keymod_checking),
            tone = Tone.INFO,
        ) {
            Spacer(Modifier.height(8.dp))
            Progress()
        }
        return
    }

    val tone = when (status.state) {
        KeyboardModule.State.ACTIVE -> Tone.OK
        KeyboardModule.State.NOT_INSTALLED, KeyboardModule.State.PENDING_REBOOT -> Tone.INFO
        else -> Tone.WARN
    }
    val body = when (status.state) {
        KeyboardModule.State.NO_ROOT -> R.string.keymod_noroot
        KeyboardModule.State.NO_MAGISK -> R.string.keymod_nomagisk
        KeyboardModule.State.NOT_INSTALLED -> R.string.keymod_notinstalled
        KeyboardModule.State.PENDING_REBOOT -> R.string.keymod_pending
        KeyboardModule.State.NOT_LOADED -> R.string.keymod_notloaded
        KeyboardModule.State.INCOMPLETE -> R.string.keymod_incomplete
        KeyboardModule.State.ACTIVE -> R.string.keymod_active
    }

    val (container, onContainer) = toneColors(tone)
    val actionColors = ButtonDefaults.buttonColors(
        containerColor = onContainer,
        contentColor = container,
    )

    /**
     * Reboot asks first.
     *
     * It used to fire on the first touch, from a card that is on screen by default
     * whenever the module is waiting for a restart - so a stray tap, or a cursor landing
     * in the wrong place, restarted the tablet with nothing to undo it. Everything else on
     * this screen is recoverable by tapping it again; this one is not, and it is the only
     * control here that interrupts whatever else the device was doing.
     */
    var confirmReboot by remember { mutableStateOf(false) }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text(stringResource(R.string.confirm_reboot_title)) },
            text = { Text(stringResource(R.string.confirm_reboot_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReboot = false; onReboot() }) {
                    Text(stringResource(R.string.action_reboot))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReboot = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    val askReboot = { confirmReboot = true }

    NoticeCard(
        title = stringResource(R.string.keymod_title),
        body = stringResource(body),
        tone = tone,
        leading = if (status.state == KeyboardModule.State.ACTIVE) {
            { StatusBadge(ok = true) }
        } else {
            null
        },
    ) {
        if (status.state != KeyboardModule.State.NO_ROOT &&
            status.state != KeyboardModule.State.NO_MAGISK
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.keymod_detail,
                    status.patchedLayouts,
                    status.altRightLayouts,
                    status.magiskVersion,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        }
        if (status.markers.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.keymod_markers, status.markers.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(10.dp))
        if (busy) {
            Progress()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status.state) {
                    KeyboardModule.State.NO_ROOT ->
                        Button(onClick = onRetry, colors = actionColors) { Text(stringResource(R.string.action_retry)) }

                    KeyboardModule.State.NO_MAGISK -> Unit

                    KeyboardModule.State.NOT_INSTALLED ->
                        Button(onClick = onInstall, colors = actionColors) { Text(stringResource(R.string.action_install)) }

                    KeyboardModule.State.PENDING_REBOOT ->
                        Button(onClick = askReboot, colors = actionColors) { Text(stringResource(R.string.action_reboot)) }

                    KeyboardModule.State.NOT_LOADED -> {
                        Button(onClick = askReboot, colors = actionColors) { Text(stringResource(R.string.action_reboot)) }
                        if (status.markers.isNotEmpty()) {
                            TextButton(onClick = onClearMarkers) {
                                Text(stringResource(R.string.action_clear_markers))
                            }
                        }
                    }

                    KeyboardModule.State.INCOMPLETE -> {
                        Button(onClick = onInstall, colors = actionColors) { Text(stringResource(R.string.action_reinstall)) }
                        TextButton(onClick = askReboot) { Text(stringResource(R.string.action_reboot)) }
                    }

                    KeyboardModule.State.ACTIVE ->
                        // No horizontal content padding, so the label lines up with the title and
                        // body above it instead of sitting a button-inset in from the card edge.
                        TextButton(
                            onClick = onInstall,
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) { Text(stringResource(R.string.action_reinstall)) }
                }
            }
        }

        if (output.isNotEmpty()) Mono(output.take(1200))
    }
}

/** A progress bar that takes its colour from the card it is sitting on. */
@Composable
private fun Progress() {
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = LocalContentColor.current,
        trackColor = LocalContentColor.current.copy(alpha = 0.24f),
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("cmd", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

/**
 * Asks Control - which lives inside com.zui.launcher - to re-read every config layer and
 * report what it can see. Implicit, because Control registers its receiver at runtime and
 * there is no component to address; the package is pinned so the intent is delivered to the
 * launcher only and not offered to every receiver on the device.
 */
private fun sendReload(context: Context) {
    context.sendBroadcast(
        Intent(Channels.CONTROL_ACTION)
            .setPackage(Channels.TARGET_PKG)
            .putExtra(Channels.EXTRA_METHOD, Channels.CMD_RELOAD)
    )
    Toast.makeText(context, R.string.reload_sent, Toast.LENGTH_SHORT).show()
}
