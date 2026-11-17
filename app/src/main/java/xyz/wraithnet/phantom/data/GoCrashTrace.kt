package xyz.wraithnet.phantom.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads EXISTING Go crash output written by libbox.Setup() through
 * debug.SetCrashOutput(). It does not modify the Go engine, connect servers,
 * capture logs in real time, or upload any data.
 *
 * The libbox 1.14.4 upstream uses:
 * files/singbox/CrashReport-.log, plus crash_reports/<timestamp>/go.log
 * when CrashReportSource is left blank (as it is in Phantom 0.2.22).
 *
 * Only bounded, privacy-filtered Go fatal headers, function names and source
 * file basenames are copied; no full dump or pointer values are shown.
 */
object GoCrashTrace {
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val MAX_LINES = 58
    private const val MAX_REPORTS = 3

    fun report(context: Context): String = runCatching {
        val root = context.filesDir.resolve("singbox")
        val archiveRoot = root.resolve("crash_reports")
        val candidates = mutableListOf(root.resolve("CrashReport-.log"))
        archiveRoot.listFiles()?.filter { it.isDirectory }
            ?.forEach { folder -> candidates.add(folder.resolve("go.log")) }
        val sources = candidates.filter { it.isFile && it.length() > 0L }
            .sortedByDescending(File::lastModified)
            .take(MAX_REPORTS)

        if (sources.isEmpty()) {
            return@runCatching "Отчёты Go: файлов с аварийным завершением пока нет."
        }
        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val summaries = sources.mapNotNull { source ->
            val bytes = source.inputStream().use { input ->
                val buffer = ByteArray(MAX_FILE_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n <= 0) break
                    total += n
                }
                buffer.copyOf(total)
            }
            val summary = summarize(bytes.toString(Charsets.UTF_8))
            if (summary.isBlank()) null else {
                val stamp = formatter.format(Date(source.lastModified()))
                "$stamp\n$summary"
            }
        }
        if (summaries.isEmpty()) {
            "Отчёты Go найдены, но в них нет распознанной фатальной ошибки."
        } else {
            "Сохранённые аварии Go (без ключей и конфигураций):\n\n" +
                summaries.joinToString("\n\n---\n\n")
        }
    }.getOrDefault("Не удалось прочитать сохранённые аварийные отчёты Go.")

    /** Sanitised subset of a native Go fatal stack, safe for user clipboard. */
    fun summarize(text: String): String {
        val lines = text.lineSequence().take(12000).toList()
        val start = lines.indexOfFirst {
            val s = it.trimStart()
            s.startsWith("fatal error:") || s.startsWith("panic:") ||
                s.startsWith("runtime:") || s.startsWith("unexpected fault address") ||
                s.startsWith("[signal SIG") || s.startsWith("SIGABRT")
        }
        if (start < 0) return ""
        val out = mutableListOf<String>()
        for (raw in lines.drop(start).take(420)) {
            if (out.size >= MAX_LINES) break
            val s = raw.trim()
            if (s.isEmpty()) continue
            val safe = when {
                s.startsWith("fatal error:") || s.startsWith("panic:") ||
                    s.startsWith("runtime:") || s.startsWith("[signal SIG") ||
                    s.startsWith("unexpected fault address") ->
                    redact(s).take(250)
                Regex("^goroutine [0-9]+ \\[.*\\]:").containsMatchIn(s) ->
                    s.substringBefore(']') .plus("]").take(130)
                s.startsWith("created by ") ->
                    "created by " + symbolOnly(s.removePrefix("created by "))
                // Go function frames may contain arbitrary arguments: omit them.
                s.contains("(") && s.contains(")") &&
                    (s.contains(".") || s.contains("/")) &&
                    !s.startsWith("0x") -> symbolOnly(s)
                // Source stack: do not leak paths or inlined parameter values.
                s.contains(".go:") -> {
                    val name = s.substringBefore(':').substringAfterLast('/')
                    val location = s.substringAfter(".go:", "")
                        .takeWhile { it.isDigit() }
                    if (location.isNotEmpty()) "$name:$location" else name
                }
                else -> null
            }
            if (!safe.isNullOrBlank()) out.add(safe)
        }
        return out.joinToString("\n")
    }

    private fun symbolOnly(line: String): String {
        val beforeArgs = line.substringBefore('(').trim().take(180)
        return redact(beforeArgs)
    }

    private fun redact(s: String): String = s
        .replace(Regex("(?i)(?:vless|vmess|tuic|anytls|ss|trojan|hysteria2?|https?|wss?)://\\S+"),
            "[ссылка скрыта]")
        .replace(Regex("(?i)\\b(?:password|passwd|secret|token|authorization|uuid|key)\\s*[=:]\\s*[^\\s,;]+"),
            "[ключ скрыт]")
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[IP]")
        .replace(Regex("(?i)(?:/[a-z0-9_.-]+){2,}"), "[путь]")
        .replace(Regex("[A-Za-z0-9+/]{60,}={0,2}"), "[длинное значение]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
}
