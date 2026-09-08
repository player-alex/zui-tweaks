package io.laelaps.zuitweaks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.laelaps.zuitweaks.settings.SettingsStore
import io.laelaps.zuitweaks.ui.AppSelectionScreen
import io.laelaps.zuitweaks.ui.SettingsScreen
import io.laelaps.zuitweaks.ui.theme.ZuiTweaksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The module's only screen.
 *
 * Nothing here may touch io.laelaps.zuitweaks.xposed: those classes reference the
 * Xposed API, which is a compileOnly dependency and is simply absent from the APK in this
 * process. The settings layer under settings/ is the shared, Xposed-free half.
 */
class MainActivity : ComponentActivity() {

    /**
     * Null until the store is open. SettingsStore.open() goes through the libXposed service
     * and writes the preferences file on first run, so it is disk and IPC - not something to
     * do on the main thread while the window is being created.
     */
    private var store by mutableStateOf<SettingsStore?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch(Dispatchers.IO) {
            store = SettingsStore.open(this@MainActivity)
        }
        setContent {
            ZuiTweaksTheme {
                var showApps by rememberSaveable { mutableStateOf(false) }
                val opened = store
                when {
                    showApps -> AppSelectionScreen(onBack = { showApps = false })
                    opened == null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    else -> SettingsScreen(opened, onOpenAppSelection = { showApps = true })
                }
            }
        }
    }
}
