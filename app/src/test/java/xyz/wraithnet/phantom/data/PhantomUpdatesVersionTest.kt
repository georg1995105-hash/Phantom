package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class PhantomUpdatesVersionTest {
    @org.junit.Test fun everySupportedArchitectureSelectsTheSameUniversalOta() {
        for (abis in listOf(
            listOf("arm64-v8a", "armeabi-v7a"),
            listOf("armeabi-v7a"),
            listOf("x86"),
            listOf("x86_64", "x86"),
            listOf("x86_64", "arm64-v8a"),
        )) {
            assertEquals("phantom-universal-release.apk", PhantomUpdates.assetFor(abis))
        }
        assertNull(PhantomUpdates.assetFor(listOf("unknown")))
        assertNull(PhantomUpdates.assetFor(emptyList()))
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
