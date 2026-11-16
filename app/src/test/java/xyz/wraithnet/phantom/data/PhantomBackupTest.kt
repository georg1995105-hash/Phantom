package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhantomBackupTest {
    private fun subscription(id: String, source: String) = JSONObject()
        .put("id", id)
        .put("title", "Subscription $id")
        .put("source", source)
        .put("servers", JSONArray().put(JSONObject()
            .put("id", "node-$id")
            .put("name", "Test")
            .put("scheme", "vless")
            .put("raw", "vless://secret@example.org:443")))

    @Test fun roundtripKeepsFullSubscriptionAndSelectedApps() {
        val input = JSONArray().put(subscription("one", "https://example.org/link"))
        val preferences = JSONObject()
            .put("theme", "FOREST")
            .put("keep_active", true)
            .put("app_route_mode", "only")
            .put("app_route_packages", JSONArray()
                .put("com.openai.chatgpt").put("org.mozilla.firefox"))
        val json = PhantomBackup.encode(input.toString(), preferences, "device-123")
        val snapshot = PhantomBackup.parse(json)
        assertEquals(1, snapshot.count)
        assertEquals("device-123", snapshot.deviceId)
        assertEquals("FOREST", snapshot.settings.getString("theme"))
        assertEquals(2, snapshot.settings.getJSONArray("app_route_packages").length())
        assertTrue(snapshot.subscriptions.getJSONObject(0).getJSONArray("servers")
            .getJSONObject(0).getString("raw").contains("secret"))
    }

    @Test fun mergesWithoutDeletingUnrelatedSubscriptions() {
        val current = JSONArray().put(subscription("one", "https://example.org/one"))
        val imported = JSONArray().put(subscription("two", "https://example.org/two"))
        val merged = PhantomBackup.merge(current, imported)
        assertEquals(2, merged.length())
        assertEquals("one", merged.getJSONObject(0).getString("id"))
        assertEquals("two", merged.getJSONObject(1).getString("id"))
    }

    @Test fun sameSubscriptionIsUpdatedNotDuplicated() {
        val source = "https://example.org/sub"
        val current = JSONArray().put(subscription("one", source))
        val imported = JSONArray().put(subscription("two", source))
        val merged = PhantomBackup.merge(current, imported)
        assertEquals(1, merged.length())
        assertEquals("two", merged.getJSONObject(0).getString("id"))
    }

    @Test fun rejectsWrongFormatAndMalformedProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            PhantomBackup.parse("""{"format":"something-else","version":1,"settings":{},"subscriptions":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhantomBackup.encode(
                JSONArray().put(JSONObject().put("id","id").put("title","Missing servers")
                    .put("source","")).toString(), JSONObject(), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhantomBackup.parse("x".repeat(PhantomBackup.MAX_SIZE + 1))
        }
    }

    @Test fun rejectsUnexpectedSettingsTypes() {
        val prefs = JSONObject().put("app_route_packages", "not an array")
        assertThrows(IllegalArgumentException::class.java) {
            PhantomBackup.encode("[]", prefs, null)
        }
    }
}
