package xyz.wraithnet.phantom.data

/** Deterministic ordering for per-app VPN; filtering never mutates selection. */
object AppSelectionGroups {
    data class App(val packageName: String, val title: String, val system: Boolean)
    data class Groups(val selected: List<App>, val available: List<App>, val hiddenSystem: Int)

    fun group(
        all: List<App>,
        selected: Set<String>,
        search: String,
        showSystem: Boolean,
    ): Groups {
        val byPackage = all.associateBy { it.packageName }
        val merged = all + (selected - byPackage.keys).map { missing ->
            App(missing, missing, false)
        }
        val matching = merged.filter {
            search.isBlank() || it.title.contains(search, ignoreCase = true) ||
                it.packageName.contains(search, ignoreCase = true)
        }
        val sorted = compareBy<App>({ it.title.lowercase() }, { it.packageName })
        val chosen = matching.filter { it.packageName in selected }.sortedWith(sorted)
        val candidates = matching.filter { it.packageName !in selected }
        return Groups(
            selected = chosen,
            available = candidates.filter { showSystem || !it.system }.sortedWith(sorted),
            hiddenSystem = candidates.count { it.system && !showSystem }
        )
    }
}
