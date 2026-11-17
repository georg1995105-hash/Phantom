package xyz.wraithnet.phantom.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads Android's own historical native-crash tombstones without root, ADB,
 * network access or an external crash SDK.
 *
 * Returns the crashing thread's signal/frames, Android's explicit abort_message
 * and narrowly filtered fatal messages of the same crashing PID. All text is
 * redacted before display; no registers, raw tombstone, other-process logs,
 * passwords, subscription links, network addresses or process memory.
 *
 * AOSP schema: debuggerd/proto/tombstone.proto
 * ApplicationExitInfo.getTraceInputStream() supplies protobuf on API 31+.
 */
object NativeCrashTrace {
    private const val MAX_TOMBSTONE_BYTES = 4 * 1024 * 1024

    fun report(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Для отчёта требуется Android 11 или новее."
        }
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val process = "${context.packageName}:singbox"
            val crashes = manager.getHistoricalProcessExitReasons(context.packageName, 0, 64)
                .asSequence()
                .filter {
                    it.processName == process &&
                        it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                }
                .take(3)
                .toList()
            if (crashes.isEmpty()) {
                return@runCatching "Android не сохранил сведения о нативных сбоях sing-box."
            }
            val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            crashes.mapIndexed { index, exit ->
                val header = "${index + 1}. sing-box: " +
                    formatter.format(Date(exit.timestamp)) +
                    ", PID ${exit.pid}, код завершения ${exit.status}"
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    return@mapIndexed "$header\nНативная трассировка доступна с Android 12."
                }
                val bytes = runCatching {
                    exit.traceInputStream?.use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (total < MAX_TOMBSTONE_BYTES) {
                                val count = input.read(
                                    buffer, 0,
                                    minOf(buffer.size, MAX_TOMBSTONE_BYTES - total)
                                )
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                total += count
                            }
                            output.toByteArray()
                        }
                    }
                }.getOrNull()
                "$header\n" + if (bytes == null || bytes.isEmpty()) {
                    "Android не сохранил tombstone для этого сбоя."
                } else {
                    runCatching { summarize(bytes) }.getOrDefault(
                        "Системная трассировка повреждена либо имеет неизвестный формат."
                    )
                }
            }.joinToString("\n\n")
        }.getOrElse { "Не удалось прочитать историю падений Android." }
    }

    /** Exposed only for pure JVM regression tests against AOSP protobuf tags. */
    fun summarize(tombstone: ByteArray): String {
        var tid = -1L
        var pid = -1L
        var signal = ""
        var code = ""
        var abortMessage = ""
        val crashLogBuffers = ArrayList<ByteArray>()
        val threadEntries = ArrayList<Pair<Long, ByteArray>>()
        val root = Fields(tombstone)
        while (true) {
            val f = root.next() ?: break
            when (f.number) {
                5 -> if (f.wire == 0) pid = f.value
                6 -> if (f.wire == 0) tid = f.value
                10 -> if (f.wire == 2) {
                    val signalFields = Fields(root.slice(f))
                    while (true) {
                        val sf = signalFields.next() ?: break
                        when (sf.number) {
                            2 -> if (sf.wire == 2) signal = signalFields.string(sf)
                            4 -> if (sf.wire == 2) code = signalFields.string(sf)
                        }
                    }
                }
                14 -> if (f.wire == 2) abortMessage = root.string(f)
                18 -> if (f.wire == 2 && crashLogBuffers.size < 4) {
                    crashLogBuffers.add(root.slice(f))
                }
                16 -> if (f.wire == 2 && threadEntries.size < 64) {
                    val mapping = Fields(root.slice(f))
                    var mapKey = -1L
                    var threadBytes: ByteArray? = null
                    while (true) {
                        val m = mapping.next() ?: break
                        if (m.number == 1 && m.wire == 0) mapKey = m.value
                        if (m.number == 2 && m.wire == 2) threadBytes = mapping.slice(m)
                    }
                    if (threadBytes != null) threadEntries.add(mapKey to threadBytes)
                }
            }
        }
        val output = StringBuilder()
        output.append("Сигнал: ").append(safe(signal.ifBlank { "не указан" }))
        if (code.isNotBlank()) output.append(" (").append(safe(code)).append(")")
        if (abortMessage.isNotBlank()) {
            output.append("\nAbort message: ").append(safeDiagnostic(abortMessage))
        } else {
            output.append("\nAbort message: отсутствует в tombstone")
        }
        val fatalLines = findFatalCrashLines(crashLogBuffers, pid, tid)
        if (fatalLines.isNotEmpty()) {
            output.append("\nПоследние аварийные сообщения процесса:")
            fatalLines.forEach { output.append("\n").append(it) }
        }
        val crashingThread = threadEntries.firstOrNull { it.first == tid }?.second
        if (crashingThread == null) {
            output.append("\nСтек аварийного потока отсутствует.")
            return output.toString()
        }
        val threadFields = Fields(crashingThread)
        var threadName = ""
        val frames = ArrayList<String>()
        val unwindNotes = ArrayList<String>()
        while (true) {
            val f = threadFields.next() ?: break
            when (f.number) {
                2 -> if (f.wire == 2) threadName = threadFields.string(f)
                7 -> if (f.wire == 2 && unwindNotes.size < 2) {
                    unwindNotes.add(safeDiagnostic(threadFields.string(f)))
                }
                4 -> if (f.wire == 2 && frames.size < 15) {
                    val frame = Fields(threadFields.slice(f))
                    var pc = -1L
                    var fn = ""
                    var file = ""
                    while (true) {
                        val part = frame.next() ?: break
                        when (part.number) {
                            1 -> if (part.wire == 0) pc = part.value
                            4 -> if (part.wire == 2) fn = frame.string(part)
                            6 -> if (part.wire == 2) file = frame.string(part)
                        }
                    }
                    // No raw full paths: tombstones can contain private files.
                    val lib = safe(file.substringAfterLast('/').takeLast(96))
                    val symbol = safe(fn.take(140))
                    val address = if (pc >= 0) "pc 0x${pc.toString(16)}" else "pc ?"
                    frames.add("#${frames.size} $address ${lib.ifBlank { "?" }} " +
                        symbol.ifBlank { "(без символа)" })
                }
            }
        }
        output.append("\nПоток: ").append(safe(threadName.ifBlank { "не указан" }))
        if (frames.isEmpty()) output.append("\nСтек отсутствует.")
        else frames.forEach { output.append("\n").append(it) }
        unwindNotes.filter(String::isNotBlank)
            .forEach { output.append("\nПримечание развёртки: ").append(it) }
        return output.toString()
    }

    /**
     * Tombstone LogBuffer #18 is optional. Never expose arbitrary logcat.
     * Only severe lines from the exact crashed process ID can contribute.
     */
    private fun findFatalCrashLines(buffers: List<ByteArray>, pid: Long, tid: Long): List<String> {
        if (pid <= 0) return emptyList()
        val hits = ArrayList<String>()
        for (buffer in buffers) {
            val messages = Fields(buffer)
            while (true) {
                val entry = messages.next() ?: break
                if (entry.number != 2 || entry.wire != 2) continue
                val record = Fields(messages.slice(entry))
                var logPid = -1L
                var logTid = -1L
                var priority = 0L
                var tag = ""
                var message = ""
                while (true) {
                    val field = record.next() ?: break
                    when (field.number) {
                        2 -> if (field.wire == 0) logPid = field.value
                        3 -> if (field.wire == 0) logTid = field.value
                        4 -> if (field.wire == 0) priority = field.value
                        5 -> if (field.wire == 2) tag = record.string(field)
                        6 -> if (field.wire == 2) message = record.string(field)
                    }
                }
                if (logPid != pid || priority < 6) continue
                // Show only known failure signatures; no general process logs.
                if (!Regex("(?i)jni detected error|fatal error:|panic:|abort|assertion|runtime:|signal 6|sigabrt")
                        .containsMatchIn(message)) continue
                val line = safeDiagnostic(message)
                if (line.isNotBlank()) {
                    hits.add("${safe(tag.take(35))} " +
                        (if (logTid == tid) "[поток сбоя] " else "") + line)
                }
            }
        }
        return hits.takeLast(6)
    }

    /**
     * Explicit privacy filter for user-visible error-only text. Avoid URLs,
     * credentials, IP addresses, absolute file paths and long opaque tokens.
     */
    private fun safeDiagnostic(input: String): String = input
        .replace(Regex("(?i)(?:tuic|anytls|vless|vmess|ss|trojan|hysteria2?|https?|wss?)://\\S+"),
            "[ссылка скрыта]")
        .replace(Regex("(?i)\\b(password|passwd|secret|token|authorization|uuid|key)\\s*[=:]\\s*[^\\s,;]+"),
            "$1=[скрыто]")
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[IP скрыт]")
        .replace(Regex("(?i)(?:/[a-z0-9_.-]+){2,}"), "[путь скрыт]")
        .replace(Regex("[A-Za-z0-9+/]{60,}={0,2}"), "[данные скрыты]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(350)

    private fun safe(input: String): String =
        input.replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("""(?i)(?:vless|vmess|tuic|anytls|ss|trojan|https?)://\S+"""), "[URL]")
            .replace(Regex("""(?i)(?:password|secret|token|key)[=:]\S+"""), "[скрыто]")
            .take(160)

    private class Fields(private val input: ByteArray) {
        private var offset = 0
        data class Field(val number: Int, val wire: Int, val value: Long, val start: Int, val end: Int)

        private fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(offset < input.size) { "Обрезанный protobuf" }
                val b = input[offset++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
            }
            error("Неверная длина protobuf varint")
        }

        fun next(): Field? {
            if (offset >= input.size) return null
            val tag = varint()
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            require(number > 0) { "Некорректное поле protobuf" }
            return when (wire) {
                0 -> Field(number, wire, varint(), offset, offset)
                1 -> {
                    require(offset + 8 <= input.size)
                    val f = Field(number, wire, 0, offset, offset + 8)
                    offset += 8
                    f
                }
                2 -> {
                    val size = varint()
                    require(size >= 0 && size <= input.size - offset)
                    val start = offset
                    offset += size.toInt()
                    Field(number, wire, 0, start, offset)
                }
                5 -> {
                    require(offset + 4 <= input.size)
                    val f = Field(number, wire, 0, offset, offset + 4)
                    offset += 4
                    f
                }
                else -> error("Неизвестный protobuf wire type")
            }
        }

        fun slice(f: Field): ByteArray = input.copyOfRange(f.start, f.end)
        fun string(f: Field): String =
            String(input, f.start, minOf(f.end - f.start, 240), Charsets.UTF_8)
    }
}
