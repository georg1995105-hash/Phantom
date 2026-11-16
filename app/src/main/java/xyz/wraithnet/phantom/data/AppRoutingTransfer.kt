package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject

/** Local-only transfer of per-app VPN settings. No remote request or privileged package access. */
object AppRoutingTransfer {
    private const val MAX_CHARS = 100_000
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    data class Selection(val mode: String?, val packages: Set<String>)

    fun export(mode: String, packages: Set<String>): String {
        require(mode in setOf(AppRouting.ALL, AppRouting.ONLY, AppRouting.EXCLUDE))
        return JSONObject()
            .put("format", "phantom-app-routing")
            .put("version", 1)
            .put("mode", mode)
            .put("packages", JSONArray(packages.sorted()))
            .toString(2)
    }

    /**
     * Imports Phantom's JSON export or plain package-name lines.
     * Does not apply preferences until the user presses Apply in the UI.
     */
    fun parse(input: String): Selection {
        val text = input.trim().removePrefix("\uFEFF")
        require(text.isNotBlank() && text.length <= MAX_CHARS) {
            "Пустой или слишком большой список приложений"
        }
        if (text.startsWith("{")) {
            val root = JSONObject(text)
            val mode = root.optString("mode").takeIf {
                it in setOf(AppRouting.ALL, AppRouting.ONLY, AppRouting.EXCLUDE)
            }
            val list = root.optJSONArray("packages")
                ?: root.optJSONArray("apps")
                ?: throw IllegalArgumentException("В файле нет списка приложений")
            require(list.length() <= 5000) { "Слишком много приложений" }
            return Selection(mode, (0 until list.length()).map { list.optString(it).trim() }
                .filter { packagePattern.matches(it) }.toSet())
        }
        val packages = text.lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore(" #").trim() }
            .filter { packagePattern.matches(it) }.toSet()
        require(packages.isNotEmpty()) { "Не найдены имена пакетов приложений" }
        return Selection(null, packages)
    }
}
