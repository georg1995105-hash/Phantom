package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class PhantomUpdatesVersionTest {
    @Test fun stableOtaUsesFirstSupportedDeviceAbiInsteadOfFourTimesLargerUniversalApk() {
        val cases = mapOf(
            listOf("arm64-v8a", "armeabi-v7a") to "phantom-arm64-v8a-release.apk",
            listOf("armeabi-v7a") to "phantom-armeabi-v7a-release.apk",
            listOf("x86") to "phantom-x86-release.apk",
            listOf("x86_64", "x86") to "phantom-x86_64-release.apk",
            listOf("x86_64", "arm64-v8a") to "phantom-x86_64-release.apk",
            listOf("unknown", "arm64-v8a") to "phantom-arm64-v8a-release.apk",
        )
        for ((abis, expected) in cases) {
            assertEquals(expected, PhantomUpdates.assetFor(abis))
            assertEquals(
                listOf(expected, "phantom-universal-release.apk"),
                PhantomUpdates.assetCandidates(abis)
            )
        }
        assertNull(PhantomUpdates.assetFor(listOf("unknown")))
        assertNull(PhantomUpdates.assetFor(emptyList()))
        assertTrue(PhantomUpdates.assetCandidates(emptyList()).isEmpty())
    }

    @Test fun stableFallbackIsUniversalWhenMatchingArchitectureIsUnavailableInRelease() {
        assertEquals(
            listOf("phantom-arm64-v8a-release.apk", "phantom-universal-release.apk"),
            PhantomUpdates.assetCandidates(listOf("arm64-v8a", "armeabi-v7a"))
        )
    }

    @Test fun betaHasIndependentUniversalAssetAndChannel() {
        assertEquals("phantom-beta-universal-release.apk",
            PhantomUpdates.assetFor(listOf("arm64-v8a"), "beta"))
        assertEquals(listOf("phantom-beta-universal-release.apk"),
            PhantomUpdates.assetCandidates(listOf("x86_64"), "beta"))
        assertFalse(PhantomUpdates.eligibleForBeta(false, false, "0.3.7-beta.1"))
        assertFalse(PhantomUpdates.eligibleForBeta(true, true, "0.3.7-beta.1"))
        assertFalse(PhantomUpdates.eligibleForBeta(false, true, "0.3.7"))
        assertTrue(PhantomUpdates.eligibleForBeta(false, true, "0.3.7-beta.1"))
        assertTrue(PhantomUpdates.isNewerForChannel("0.3.7-beta.2", "0.3.7-beta.1", "beta"))
        assertTrue(PhantomUpdates.isNewerForChannel("0.3.8-beta.1", "0.3.7-beta.99", "beta"))
        assertFalse(PhantomUpdates.isNewerForChannel("0.3.8", "0.3.7-beta.1", "beta"))
        assertFalse(PhantomUpdates.isNewerForChannel("0.3.7-beta.1", "0.3.7-beta.2", "beta"))
        assertFalse(PhantomUpdates.isNewerForChannel("0.3.7-beta.2", "0.3.7", "stable"))
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
