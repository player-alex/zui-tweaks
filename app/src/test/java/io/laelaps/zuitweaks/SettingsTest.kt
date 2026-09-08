package io.laelaps.zuitweaks

import io.laelaps.zuitweaks.settings.Labels
import io.laelaps.zuitweaks.settings.NumberSetting
import io.laelaps.zuitweaks.settings.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two ways the settings tables can silently drift apart.
 */
class SettingsTest {

    /** Schema deliberately does not reference R, so nothing but this test links them. */
    @Test
    fun everySettingHasLabels() {
        val missing = Schema.all.map { it.key }.filterNot { it in Labels.keys }
        assertEquals("schema keys with no entry in Labels", emptyList<String>(), missing)
        val orphaned = Labels.keys.filterNot { key -> Schema.all.any { it.key == key } }
        assertEquals("Labels entries with no setting", emptyList<String>(), orphaned)
    }

    @Test
    fun keysAreUnique() {
        val dupes = Schema.keysLowercase.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate keys", emptySet<String>(), dupes)
    }

    /**
     * Compose needs the default to sit exactly on a slider notch, otherwise the thumb
     * jumps the first time it is touched.
     */
    @Test
    fun numericDefaultsLandOnAStep() {
        Schema.all.filterIsInstance<NumberSetting>().forEach {
            assertEquals(it.key, it.default, it.clamp(it.default))
            assertTrue("${it.key}: range must divide evenly by step", (it.max - it.min) % it.step == 0)
            assertTrue("${it.key}: default out of range", it.default in it.min..it.max)
            assertTrue("${it.key}: range must fit at least one step", (it.max - it.min) / it.step >= 1)
        }
    }
}
