package com.arny.ffmpegcompose.data

import com.arny.ffmpegcompose.components.home.ConvertType
import com.arny.ffmpegcompose.components.utils.toDurationUs
import com.arny.ffmpegcompose.data.models.ConversionParams
import com.arny.ffmpegcompose.data.models.TrimStrategy
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaTimeTest {
    @Test
    fun durationFromFfprobeUsesMicrosecondsExplicitly() {
        assertEquals(1_500_000L, "1.5".toDurationUs())
    }

    @Test
    fun timeInputIsStrictAndSupportsMilliseconds() {
        assertEquals(3_723_456L, TimeUtils.parseToMs("01:02:03.456"))
        assertFailsWith<IllegalArgumentException> { TimeUtils.parseToMs("00:61:00") }
        assertFailsWith<IllegalArgumentException> { TimeUtils.parseToMs("12:34") }
    }

    @Test
    fun ffmpegTimeAlwaysUsesDotRegardlessOfSystemLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            val params = ConversionParams(
                inputFile = "input.mp4",
                outputFile = "output.mp4",
                convertType = ConvertType.CONVERT,
            )
            assertEquals("00:00:01.500", params.formatTimeMs(1_500L))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun transcriptionUsesAccurateTrim() {
        val params = ConversionParams(
            inputFile = "input.mp4",
            outputFile = "output.srt",
            convertType = ConvertType.TRANSCRIBE,
            trimStartMs = 1_000L,
            trimStrategy = TrimStrategy.AUTO,
        )
        assertEquals(TrimStrategy.ACCURATE, params.getEffectiveTrimStrategy())
    }
}
