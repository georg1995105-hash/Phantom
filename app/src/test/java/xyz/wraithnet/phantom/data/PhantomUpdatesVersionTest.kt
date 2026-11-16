package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class PhantomUpdatesVersionTest {
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
