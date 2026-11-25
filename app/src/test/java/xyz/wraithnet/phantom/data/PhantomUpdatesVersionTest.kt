package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class PhantomUpdatesVersionTest {
    @Test fun automaticInstallRequiresConfirmedIdleVpn() {
        assertTrue(PhantomUpdates.automaticInstallSafe("disconnected", true))
        assertFalse(PhantomUpdates.automaticInstallSafe("disconnected", false))
        listOf("starting", "connected", "degraded", "error", "unknown").forEach {
            assertFalse(PhantomUpdates.automaticInstallSafe(it, true))
        }
    }

    @Test fun deltaNetworkFailureIsDistinctFromInvalidPatch() {
        assertTrue(PhantomUpdates.retryDeltaAfter(java.io.IOException("connection lost")))
        assertTrue(PhantomUpdates.retryDeltaAfter(PhantomHttp.HttpStatusException(503)))
        assertTrue(PhantomUpdates.retryDeltaAfter(PhantomHttp.HttpStatusException(429)))
        assertFalse(PhantomUpdates.retryDeltaAfter(PhantomHttp.HttpStatusException(404)))
        assertFalse(PhantomUpdates.retryDeltaAfter(IllegalArgumentException("invalid digest")))
    }

    @Test fun cachedApkIsQualifiedByExactVersionAndFullDigest() {
        val release = PhantomRelease("0.3.37", "", "a".repeat(64), 2000L, "")
        assertEquals("phantom-0.3.37-" + "a".repeat(64) + ".apk", PhantomUpdates.cacheName(release))
        assertNotEquals(PhantomUpdates.cacheName(release), PhantomUpdates.cacheName(release.copy(sha256 = "b".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { PhantomUpdates.cacheName(release.copy(version = "../evil")) }
        assertThrows(IllegalArgumentException::class.java) { PhantomUpdates.cacheName(release.copy(sha256 = "invalid")) }
    }

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

    @Test fun deltaAssetIsOfferedOnlyToExactInstalledOfficialVersion() {
        fun asset(name: String, urlVersion: String, size: Long) =
            org.json.JSONObject().put("name", name)
                .put("size", size)
                .put("state", "uploaded")
                .put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url",
                    "https://github.com/georg1995105-hash/Phantom/releases/download/" +
                        urlVersion + "/" + name)
        val nextTag = "v0.3.25"
        val full = asset(PhantomUpdates.UNIVERSAL_APK, nextTag, 184_000_000)
        val patch = asset("phantom-delta-from-v0.3.24.apkdelta", nextTag, 8_000_000)
        val wrongTag = asset("phantom-delta-from-v0.3.23.apkdelta", nextTag, 9_000_000)
        val json = org.json.JSONArray().put(
            org.json.JSONObject().put("tag_name", nextTag)
                .put("draft", false).put("prerelease", false)
                .put("assets", org.json.JSONArray().put(patch).put(full).put(wrongTag))
        )
        val assets = PhantomUpdates.assetCandidates(listOf("arm64-v8a"))
        val withDelta = PhantomUpdates.findAvailableRelease(
            json, "0.3.24", UpdateChannel.STABLE, assets)
        assertEquals("0.3.25", withDelta?.version)
        assertEquals("0.3.24", withDelta?.delta?.fromVersion)
        assertEquals(8_000_000L, withDelta?.delta?.sizeBytes)
        assertEquals(184_000_000L, withDelta?.sizeBytes)
        val oldVersion = PhantomUpdates.findAvailableRelease(
            json, "0.3.22", UpdateChannel.STABLE, assets)
        assertNull(oldVersion?.delta)
        val invalidUrl = org.json.JSONArray().put(org.json.JSONObject()
            .put("tag_name", nextTag).put("draft", false).put("prerelease", false)
            .put("assets", org.json.JSONArray().put(full)
                .put(asset("phantom-delta-from-v0.3.24.apkdelta",
                    "v0.3.24", 8_000_000))))
        assertNull(PhantomUpdates.findAvailableRelease(
            invalidUrl, "0.3.24", UpdateChannel.STABLE, assets)?.delta)
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

    @Test fun betaVersionsRespectStableSemverAndDoNotDowngrade() {
        assertTrue(PhantomUpdates.isNewer("0.3.25-beta.1", "0.3.24"))
        assertTrue(PhantomUpdates.isNewer("0.3.25-beta.2", "0.3.25-beta.1"))
        assertTrue(PhantomUpdates.isNewer("0.3.25", "0.3.25-beta.17"))
        assertFalse(PhantomUpdates.isNewer("0.3.24", "0.3.25-beta.1"))
        assertFalse(PhantomUpdates.isNewer("0.3.25-beta.2", "0.3.25"))
        assertFalse(PhantomUpdates.isNewer("0.3.25-beta.1", "0.3.25-beta.1"))
        assertFalse(PhantomUpdates.isNewer("0.3.25-beta.0", "0.3.24"))
        assertFalse(PhantomUpdates.isNewer("0.3.26-rc.1", "0.3.24"))
        assertFalse(PhantomUpdates.isNewer("invalid", "0.3.24"))
    }

    private fun releaseJson(version: String, isBeta: Boolean,
                            draft: Boolean = false, brokenAsset: Boolean = false) =
        org.json.JSONObject()
            .put("tag_name", "v$version")
            .put("draft", draft)
            .put("prerelease", isBeta)
            .put("body", "- Изменения")
            .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                .put("name", PhantomUpdates.UNIVERSAL_APK)
                .put("state", "uploaded")
                .put("digest", "sha256:" + "a".repeat(64))
                .put("size", 183_000_000L)
                .put("browser_download_url",
                    "https://github.com/georg1995105-hash/Phantom/releases/download/" +
                        (if (brokenAsset) "v0.3.18" else "v$version") +
                        "/" + PhantomUpdates.UNIVERSAL_APK)))

    @Test fun stableIgnoresBetasWhileBetaCanChooseTheLatestCorrectRelease() {
        val releases = org.json.JSONArray()
            .put(releaseJson("0.3.25-beta.1", true))
            .put(releaseJson("0.3.24", false))
            .put(releaseJson("0.3.25-beta.2", true))
            .put(releaseJson("0.3.26-beta.1", true, draft = true))
            .put(releaseJson("0.3.27-beta.1", true, brokenAsset = true))
        val universal = PhantomUpdates.assetCandidates(listOf("arm64-v8a"))
        val stable = PhantomUpdates.findAvailableRelease(
            releases, "0.3.23", UpdateChannel.STABLE, universal)
        assertEquals("0.3.24", stable?.version)
        assertEquals(false, stable?.isBeta)
        val beta = PhantomUpdates.findAvailableRelease(
            releases, "0.3.23", UpdateChannel.BETA, universal)
        assertEquals("0.3.25-beta.2", beta?.version)
        assertEquals(true, beta?.isBeta)
        assertNull(PhantomUpdates.findAvailableRelease(
            releases, "0.3.25-beta.2", UpdateChannel.STABLE, universal))
        assertNull(PhantomUpdates.findAvailableRelease(
            releases, "0.3.25-beta.2", UpdateChannel.BETA, universal))
    }

    @Test fun betaOptOutDisallowsStaleBetaButAllowsStable() {
        val stable = PhantomRelease("0.3.26", "", "", 0, "", false)
        val beta = PhantomRelease("0.3.27-beta.1", "", "", 0, "", true)
        assertTrue(PhantomUpdates.channelAllows(stable, UpdateChannel.STABLE))
        assertTrue(PhantomUpdates.channelAllows(stable, UpdateChannel.BETA))
        assertTrue(PhantomUpdates.channelAllows(beta, UpdateChannel.BETA))
        assertFalse(PhantomUpdates.channelAllows(beta, UpdateChannel.STABLE))
        assertFalse(PhantomUpdates.eligibleForChannel(
            draft = false, prerelease = true,
            version = "0.3.27-beta.1", channel = UpdateChannel.STABLE))
        assertTrue(PhantomUpdates.eligibleForChannel(
            draft = false, prerelease = true,
            version = "0.3.27-beta.1", channel = UpdateChannel.BETA))
        assertFalse(PhantomUpdates.eligibleForChannel(
            draft = false, prerelease = true,
            version = "0.3.27-rc.1", channel = UpdateChannel.BETA))
        assertFalse(PhantomUpdates.eligibleForChannel(
            draft = false, prerelease = false,
            version = "0.3.27-beta.1", channel = UpdateChannel.BETA))
    }

}
