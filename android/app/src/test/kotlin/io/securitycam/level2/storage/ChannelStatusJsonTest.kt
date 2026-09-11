package io.securitycam.level2.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round-trip coverage for the JSONObject/JSONArray-backed channel-status codec. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelStatusJsonTest {

    @Test
    fun mapRoundTripWithBackslashesQuotesNewlinesUnicode() {
        val original = mapOf(
            "plain" to "delivered",
            "backslash" to "C:\\clips\\hall\\a.mp4",
            "quote" to "say \"hi\"",
            "newline" to "line1\nline2\r\nline3\tend",
            "unicode" to "caf\u00e9 \uD83D\uDE00 \u4e2d\u6587",
            "empty" to "",
        )
        val encoded = jsonEncodeChannelStatusMap(original)
        assertEquals(original, decodeChannelStatusMap(encoded))
    }

    @Test
    fun listRoundTripWithBackslashesQuotesNewlinesUnicode() {
        val original = listOf(
            "motion",
            "C:\\clips\\a.mp4",
            "say \"hi\"",
            "line1\nline2",
            "caf\u00e9 \uD83D\uDE00",
            "",
        )
        val encoded = jsonEncodeChannelStatusList(original)
        assertEquals(original, decodeChannelStatusList(encoded))
    }

    @Test
    fun emptyMapAndListRoundTrip() {
        assertEquals(emptyMap<String, String>(), decodeChannelStatusMap(jsonEncodeChannelStatusMap(emptyMap())))
        assertEquals(emptyList<String>(), decodeChannelStatusList(jsonEncodeChannelStatusList(emptyList())))
        assertEquals("{}", jsonEncodeChannelStatusMap(emptyMap()))
        assertEquals("[]", jsonEncodeChannelStatusList(emptyList()))
    }

    @Test
    fun corruptPayloadsDecodeToEmpty() {
        assertEquals(emptyMap<String, String>(), decodeChannelStatusMap("not json"))
        assertEquals(emptyList<String>(), decodeChannelStatusList("not json"))
    }
}
