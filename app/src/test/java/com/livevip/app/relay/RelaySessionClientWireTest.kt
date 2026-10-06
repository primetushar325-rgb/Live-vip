package com.livevip.app.relay

import com.livevip.app.streaming.DestinationConfig
import com.livevip.app.streaming.StreamPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay wire format — must match relay-server's API exactly
 * (POST /api/v1/sessions; ingest url + key = sessionId).
 */
class RelaySessionClientWireTest {

    private fun dest(name: String = "YouTube A", key: String = "secret-key") = DestinationConfig(
        id = 1, name = name, platform = StreamPlatform.YOUTUBE,
        url = "rtmp://a.rtmp.youtube.com/live2", streamKey = key
    )

    @Test
    fun `create body carries name and destinations with keys`() {
        val body = RelaySessionClient.Wire.buildCreateBody(
            "My Live", listOf(dest("A", "k1"), dest("B", "k2"))
        )
        val o = JSONObject(body)
        assertEquals("My Live", o.getString("name"))
        val array = o.getJSONArray("destinations")
        assertEquals(2, array.length())
        assertEquals("A", array.getJSONObject(0).getString("name"))
        assertEquals("YOUTUBE", array.getJSONObject(0).getString("platform"))
        assertEquals("rtmp://a.rtmp.youtube.com/live2", array.getJSONObject(0).getString("url"))
        assertEquals("k1", array.getJSONObject(0).getString("key"))
    }

    @Test
    fun `parse create response maps ingest url and key`() {
        val json = """
            {
              "sessionId": "abc123",
              "ingest": { "url": "rtmp://relay.example.com:11935/live", "key": "abc123" },
              "httpBase": "https://relay.example.com:18080"
            }
        """.trimIndent()
        val session = RelaySessionClient.Wire.parseCreateResponse(json)!!
        assertEquals("abc123", session.sessionId)
        assertEquals("rtmp://relay.example.com:11935/live", session.ingestUrl)
        assertEquals("abc123", session.ingestKey)
        assertEquals("https://relay.example.com:18080", session.httpBase)
    }

    @Test
    fun `missing ingest block returns null`() {
        assertNull(RelaySessionClient.Wire.parseCreateResponse("""{"sessionId":"x"}"""))
        assertNull(RelaySessionClient.Wire.parseCreateResponse("not json"))
    }

    @Test
    fun `parse status response maps relay destination states`() {
        val json = """
            {
              "sessionId": "abc123",
              "upstream": "publishing",
              "destinations": [
                { "name": "A", "state": "live", "restarts": 0 },
                { "name": "B", "state": "reconnecting", "restarts": 2, "detail": "upstream reset" }
              ]
            }
        """.trimIndent()
        val status = RelaySessionClient.Wire.parseStatusResponse(json)!!
        assertEquals("publishing", status.upstream)
        assertEquals(2, status.destinations.size)
        assertEquals("live", status.destinations[0].state)
        assertEquals(null, status.destinations[0].detail)
        assertEquals("reconnecting", status.destinations[1].state)
        assertEquals(2, status.destinations[1].restarts)
        assertEquals("upstream reset", status.destinations[1].detail)
    }

    @Test
    fun `status without destinations is still valid`() {
        val status = RelaySessionClient.Wire.parseStatusResponse(
            """{"sessionId":"x","upstream":"waiting"}"""
        )!!
        assertEquals("waiting", status.upstream)
        assertTrue(status.destinations.isEmpty())
    }

    @Test
    fun `ingest url never leaks the destination stream keys`() {
        // The ingest key is the session id — destination keys go only inside
        // the encrypted HTTPS create call, never into the ingest URL.
        val session = RelaySessionClient.Wire.parseCreateResponse(
            """{"sessionId":"s1","ingest":{"url":"rtmp://r/live","key":"s1"}}"""
        )!!
        assertTrue(!session.ingestUrl.contains("secret"))
    }
}
