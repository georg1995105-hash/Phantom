package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class AppSelectionGroupsTest {
    private val all = listOf(
        AppSelectionGroups.App("com.chrome", "Chrome", false),
        AppSelectionGroups.App("com.system", "Android services", true),
        AppSelectionGroups.App("com.messages", "Messages", false),
        AppSelectionGroups.App("com.sys2", "Hidden system", true),
    )

    @Test fun selectedFirstAndSortedRegardlessOfSystemFilter() {
        val groups = AppSelectionGroups.group(all,
            setOf("com.messages", "com.system"), "", false)
        assertEquals(listOf("com.system", "com.messages"),
            groups.selected.map { it.packageName })
        assertEquals(listOf("com.chrome"), groups.available.map { it.packageName })
        assertEquals(1, groups.hiddenSystem)
    }

    @Test fun systemAppsAvailableWhenEnabled() {
        val groups = AppSelectionGroups.group(all, emptySet(), "", true)
        assertEquals(4, groups.available.size)
        assertEquals(0, groups.hiddenSystem)
    }

    @Test fun selectedUninstalledPackageRemainsVisible() {
        val groups = AppSelectionGroups.group(all, setOf("com.removed.app"), "", false)
        assertEquals("com.removed.app", groups.selected.single().packageName)
    }

    @Test fun filteringDoesNotChangeSavedSelection() {
        val selected = setOf("com.messages", "com.system")
        val groups = AppSelectionGroups.group(all, selected, "Chrome", false)
        assertTrue(groups.selected.isEmpty())
        assertEquals("com.chrome", groups.available.single().packageName)
        assertEquals(2, selected.size)
    }
}
