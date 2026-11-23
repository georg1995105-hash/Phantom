package xyz.wraithnet.phantom.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class PhantomDeltaTest {
    private fun hash(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun scenario(test: (File, File, File, ByteArray) -> Unit) {
        val dir = Files.createTempDirectory("phantom-delta-test").toFile()
        try {
            val oldData = ByteArray(512 * 1024) { (it % 241).toByte() }
            val extra = "updated classes and manifest".toByteArray()
            val targetData = oldData + extra
            val old = File(dir, "old.apk").apply { writeBytes(oldData) }
            val patch = File(dir, "change.apkdelta")
            DataOutputStream(patch.outputStream().buffered()).use { out ->
                out.write("PHDELTA1".toByteArray(Charsets.US_ASCII))
                out.writeLong(oldData.size.toLong())
                out.writeLong(targetData.size.toLong())
                out.write(hash(oldData))
                out.write(hash(targetData))
                out.writeInt(2)
                out.writeByte(0) // COPY base range
                out.writeLong(0L)
                out.writeInt(oldData.size)
                out.writeByte(1) // INSERT literal bytes
                out.writeInt(extra.size)
                out.write(extra)
            }
            test(old, patch, File(dir, "new.apk"), targetData)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun reconstructsExactByteForByteApkAndChecksSha256() = scenario {
        old, patch, output, expected ->
        var progress = 0
        PhantomDelta.reconstruct(old, patch, output, hex(hash(expected)),
            expected.size.toLong(), onProgress = { progress = it })
        assertArrayEquals(expected, output.readBytes())
        assertTrue(progress >= 99)
        assertArrayEquals(hash(expected), PhantomDelta.sha256(output))
    }

    @Test fun refusesMismatchedSourceAndDoesNotWriteAnApk() = scenario {
        old, patch, output, expected ->
        old.appendBytes(byteArrayOf(0x01))
        val result = runCatching {
            PhantomDelta.reconstruct(old, patch, output, hex(hash(expected)),
                expected.size.toLong())
        }
        assertTrue(result.isFailure)
        assertFalse(output.exists())
    }

    @Test fun corruptedPayloadCannotProduceInstallableFile() = scenario {
        old, patch, output, expected ->
        val payload = patch.readBytes()
        payload[payload.lastIndex] = (payload.last().toInt() xor 0x44).toByte()
        patch.writeBytes(payload)
        val result = runCatching {
            PhantomDelta.reconstruct(old, patch, output, hex(hash(expected)),
                expected.size.toLong())
        }
        assertTrue(result.isFailure)
        assertFalse(output.exists())
    }

    @Test fun patchCannotClaimOutOfBoundsCopyOrOversizedTarget() = scenario {
        old, patch, output, expected ->
        val bytes = patch.readBytes()
        // First COPY offset occupies bytes 97..104. Change it to base EOF.
        val position = 8 + 8 + 8 + 32 + 32 + 4 + 1
        repeat(8) { bytes[position + it] = 0x7f }
        patch.writeBytes(bytes)
        val result = runCatching {
            PhantomDelta.reconstruct(old, patch, output, hex(hash(expected)),
                expected.size.toLong())
        }
        assertTrue(result.isFailure)
        assertFalse(output.exists())
    }
}
