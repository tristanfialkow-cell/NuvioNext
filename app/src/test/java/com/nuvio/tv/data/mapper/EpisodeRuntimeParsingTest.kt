package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.VideoDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins episode runtime parsing (`VideoDto.toDomain`) across the formats addons send. */
class EpisodeRuntimeParsingTest {

    private fun runtimeOf(value: String?): Int? = VideoDto(id = "v", runtime = value).toDomain().runtime

    @Test
    fun `minutes formats`() {
        assertEquals(24, runtimeOf("24 min"))
        assertEquals(58, runtimeOf("58m"))
        assertEquals(42, runtimeOf(" 42 MIN "))
        assertEquals(30, runtimeOf("30min"))
        assertEquals(45, runtimeOf("45 minutes"))
    }

    @Test
    fun `hours and combined formats`() {
        assertEquals(60, runtimeOf("1h"))
        assertEquals(62, runtimeOf("1h 2min"))
        assertEquals(135, runtimeOf("2 h 15 m"))
        assertEquals(90, runtimeOf("1H30M"))
    }

    @Test
    fun `bare and noisy numbers fall back to digits`() {
        assertEquals(45, runtimeOf("45"))
        assertEquals(45, runtimeOf("~45"))
        assertEquals(1530, runtimeOf("15-30"))
    }

    @Test
    fun `blank and unparseable values are null`() {
        assertEquals(null, runtimeOf(null))
        assertEquals(null, runtimeOf(""))
        assertEquals(null, runtimeOf("   "))
        assertEquals(null, runtimeOf("unknown"))
    }

    @Test
    fun `first match wins for repeated units`() {
        assertEquals(10, runtimeOf("10 min 20 min"))
        assertEquals(120, runtimeOf("2h 3h"))
    }
}
