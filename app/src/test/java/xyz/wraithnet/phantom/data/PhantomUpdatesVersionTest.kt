package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class PhantomUpdatesVersionTest {
    @Test fun allSupportedArchitecturesSelectExactlyOneUniversalOfficialApk() {
        val universal = "phantom-universal-release.apk"
        for (abis in listOf(listOf("arm64-v8a"), listOf("armeabi-v7a"),
            listOf("x86"), listOf("x86_64"), listOf("x86_64", "arm64-v8a"),
            listOf("unknown", "arm64-v8a"))) {
            assertEquals(universal, PhantomUpdates.assetFor(abis))
            assertEquals(listOf(universal), PhantomUpdates.assetCandidates(abis))
        }
        assertNull(PhantomUpdates.assetFor(listOf("unknown")))
        assertNull(PhantomUpdates.assetFor(emptyList()))
        assertTrue(PhantomUpdates.assetCandidates(emptyList()).isEmpty())
    }

    @Test fun otaIgnoresAllAbiAndBetaArtifactsAndChecksSignatureDigest() {
        fun asset(name: String, size: Long, digest: String = "a".repeat(64)) =
            org.json.JSONObject().put("name", name).put("size", size)
                .put("state", "uploaded").put("digest", "sha256:$digest")
                .put("browser_download_url",
                    "https://github.com/georg1995105-hash/Phantom/releases/download/v0.3.20/$name")
        val universal = PhantomUpdates.UNIVERSAL_APK
        val abi = "phantom-arm64-v8a-release.apk"
        val beta = "phantom-beta-universal-release.apk"
        val choices = PhantomUpdates.assetCandidates(listOf("arm64-v8a"))
        val mixed = org.json.JSONArray().put(asset(beta, 184_139_051))
            .put(asset(abi, 58_796_692)).put(asset(universal, 184_139_051))
        assertEquals(universal,
            PhantomUpdates.chooseAsset(mixed, choices)?.getString("name"))
        assertNull(PhantomUpdates.chooseAsset(
            org.json.JSONArray().put(asset(abi, 58_796_692)).put(asset(beta, 184_139_051)),
            choices))
        assertNull(PhantomUpdates.chooseAsset(
            org.json.JSONArray().put(asset(universal, 184_139_051, "bad")), choices))
        assertNull(PhantomUpdates.chooseAsset(
            org.json.JSONArray().put(asset(universal, 0)), choices))
        assertNull(PhantomUpdates.chooseAsset(
            org.json.JSONArray().put(asset(universal, 184_139_051)
                .put("state", "starter")), choices))
        assertNull(PhantomUpdates.chooseAsset(
            org.json.JSONArray().put(asset(universal, 184_139_051)
                .put("browser_download_url", "https://example.org/phantom.apk")), choices))
    }

    @Test fun resumedApkRequiresExactHttpContentRange() {
        assertTrue(PhantomUpdates.validContentRange("bytes 1024-2047/4096", 1024, 4096))
        assertTrue(PhantomUpdates.validContentRange("bytes 0-4095/4096", 0, 4096))
        assertFalse(PhantomUpdates.validContentRange("bytes 1023-2047/4096", 1024, 4096))
        assertFalse(PhantomUpdates.validContentRange("bytes 1024-4096/4096", 1024, 4096))
        assertFalse(PhantomUpdates.validContentRange("bytes 1024-2047/8192", 1024, 4096))
        assertFalse(PhantomUpdates.validContentRange("bytes */4096", 1024, 4096))
        assertFalse(PhantomUpdates.validContentRange(null, 1024, 4096))
        assertFalse(PhantomUpdates.validContentRange("bytes 1024-1023/4096", 1024, 4096))
    }

    @Test fun prereleasesCannotReachStableOta() {
        assertTrue(PhantomUpdates.eligibleForStable(draft = false, prerelease = false))
        assertFalse(PhantomUpdates.eligibleForStable(draft = false, prerelease = true))
        assertFalse(PhantomUpdates.eligibleForStable(draft = true, prerelease = false))
        assertFalse(PhantomUpdates.eligibleForStable(draft = true, prerelease = true))
    }

    @Test fun semanticVersionsCompareNumerically() {
        assertTrue(PhantomUpdates.isNewer("0.2.4", "0.2.3"))
        assertTrue(PhantomUpdates.isNewer("v1.10.0", "1.9.9"))
        assertTrue(PhantomUpdates.isNewer("2.0.0", "1.99.99"))
        assertFalse(PhantomUpdates.isNewer("0.2.3", "0.2.3"))
        assertFalse(PhantomUpdates.isNewer("0.2.2", "0.2.3"))
        assertFalse(PhantomUpdates.isNewer("latest", "0.2.3"))
    }

    @Test fun preReleaseTagsAreNotAccidentallyInstalled() {
        assertFalse(PhantomUpdates.isNewer("0.3.0-beta", "0.2.3"))
    }
}
