package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class PhantomUpdatesVersionTest {
    @org.junit.Test fun updateChoosesTheNativeDeviceArchitecture() {
        org.junit.Assert.assertEquals("phantom-x86_64-release.apk",
            PhantomUpdates.assetFor(listOf("x86_64", "arm64-v8a")))
        org.junit.Assert.assertEquals("phantom-arm64-v8a-release.apk",
            PhantomUpdates.assetFor(listOf("arm64-v8a", "armeabi-v7a")))
        org.junit.Assert.assertNull(PhantomUpdates.assetFor(listOf("armeabi-v7a")))
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
