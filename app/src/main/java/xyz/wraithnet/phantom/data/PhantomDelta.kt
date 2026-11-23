package xyz.wraithnet.phantom.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Reconstructs the EXACT signed release APK. No patch bytes are installed
 * directly. The target digest is provided independently by GitHub Releases;
 * verifyApk() in PhantomUpdates additionally checks Android package and key.
 *
 * On-disk PHDELTA1 format is implemented in scripts/build_apk_delta.py.
 * COPY reads ranges from the currently installed APK, INSERT reads the delta.
 */
internal object PhantomDelta {
    private val magic = "PHDELTA1".toByteArray(Charsets.US_ASCII)
    private const val bufferSize = 128 * 1024
    private const val maxOperations = 200_000
    private const val maxApkSize = 250L * 1024 * 1024

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /** All bounds are validated before allocating or writing each block. */
    fun reconstruct(
        installedApk: File,
        patchFile: File,
        targetFile: File,
        expectedTargetSha256: String,
        expectedTargetLength: Long,
        onProgress: (Int) -> Unit = {},
        ensureActive: () -> Unit = {},
    ) {
        require(expectedTargetSha256.matches(Regex("[a-f0-9]{64}"))) {
            "Недостоверная контрольная сумма новой версии"
        }
        require(expectedTargetLength in 1025L..maxApkSize) {
            "Недостоверный размер новой версии"
        }
        require(installedApk.isFile && patchFile.isFile) {
            "Не найден исходный APK или пакет изменений"
        }
        val expectedHash = expectedTargetSha256.chunked(2).map {
            it.toInt(16).toByte()
        }.toByteArray()
        val outputPart = File(targetFile.parentFile, targetFile.name + ".rebuild")
        outputPart.delete()
        try {
            RandomAccessFile(installedApk, "r").use { base ->
                DataInputStream(BufferedInputStream(patchFile.inputStream())).use { patch ->
                    val actualMagic = ByteArray(magic.size)
                    patch.readFully(actualMagic)
                    require(actualMagic.contentEquals(magic)) { "Неизвестный формат пакета изменений" }
                    val baseLength = patch.readLong()
                    val targetLength = patch.readLong()
                    val baseHash = ByteArray(32).also(patch::readFully)
                    val targetHash = ByteArray(32).also(patch::readFully)
                    val operationCount = patch.readInt()
                    require(baseLength == base.length() &&
                        targetLength == expectedTargetLength &&
                        targetHash.contentEquals(expectedHash) &&
                        operationCount in 1..maxOperations) {
                        "Пакет изменений не соответствует текущей версии"
                    }
                    ensureActive()
                    require(sha256(installedApk).contentEquals(baseHash)) {
                        "Установленная версия отличается от исходной"
                    }
                    val buffer = ByteArray(bufferSize)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    BufferedOutputStream(FileOutputStream(outputPart)).use { out ->
                        repeat(operationCount) {
                            ensureActive()
                            val kind = patch.readUnsignedByte()
                            val origin = if (kind == 0) patch.readLong() else 0L
                            val count = patch.readInt()
                            require(kind == 0 || kind == 1) { "Неизвестная операция патча" }
                            require(count > 0 && count.toLong() <= targetLength - written) {
                                "Ошибка длины операции патча"
                            }
                            if (kind == 0) {
                                require(origin >= 0L && origin <= baseLength &&
                                    count.toLong() <= baseLength - origin) {
                                    "Недопустимое смещение копирования APK"
                                }
                                base.seek(origin)
                            }
                            var remaining = count
                            while (remaining > 0) {
                                ensureActive()
                                val request = minOf(buffer.size, remaining)
                                val read = if (kind == 0) base.read(buffer, 0, request)
                                else patch.read(buffer, 0, request)
                                if (read <= 0) throw IOException("Пакет изменений повреждён")
                                out.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                remaining -= read
                                written += read
                                onProgress((70L + written * 29L / targetLength).toInt().coerceIn(70, 99))
                            }
                        }
                    }
                    require(written == targetLength && patch.read() == -1 &&
                        digest.digest().contentEquals(expectedHash)) {
                        "Восстановленный APK не прошёл проверку SHA-256"
                    }
                }
            }
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Не удалось заменить предыдущий APK")
            }
            require(outputPart.renameTo(targetFile)) {
                "Не удалось сохранить восстановленный APK"
            }
        } finally {
            outputPart.delete()
        }
    }
}
