package ai.unalome.pocket.features.chat

import ai.unalome.pocket.features.chat.VoiceSupport.LanguageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSupportTest {
    private fun decide(sdk: Int, onDevice: Boolean, status: LanguageStatus) = VoiceSupport.decide(sdk, onDevice, "en-US", "English", status)

    @Test
    fun onlyOnDeviceRecognitionIsEverUsed() {
        val old = decide(30, onDevice = true, LanguageStatus.INSTALLED)
        assertTrue(old is VoiceAvailability.Unavailable && old.reason.contains("Android 12"))
        val none = decide(34, onDevice = false, LanguageStatus.UNKNOWN)
        assertTrue(none is VoiceAvailability.Unavailable && none.reason.contains("Nothing is sent to a server"))
    }

    @Test
    fun languageStatusDecidesOnNewerAndroid() {
        assertEquals(VoiceAvailability.Available("en-US"), decide(34, true, LanguageStatus.INSTALLED))
        assertEquals(VoiceAvailability.NeedsDownload("en-US", "English"), decide(33, true, LanguageStatus.DOWNLOADABLE))
        assertTrue(decide(34, true, LanguageStatus.DOWNLOADING) is VoiceAvailability.Unavailable)
        val unsupported = decide(34, true, LanguageStatus.UNSUPPORTED)
        assertEquals(
            "On-device speech recognition for English is not installed on this phone, so voice input is off. Nothing is sent to a server instead.",
            (unsupported as VoiceAvailability.Unavailable).reason,
        )
        // Android 12 cannot report languages: try, and explain any error then.
        assertEquals(VoiceAvailability.Available("en-US"), decide(31, true, LanguageStatus.UNKNOWN))
        // Android 12 cannot download a language for the app either.
        assertTrue(decide(32, true, LanguageStatus.DOWNLOADABLE) is VoiceAvailability.Unavailable)
    }

    @Test
    fun languageFallsBackToTheSameLanguageElsewhere() {
        assertEquals("ro-RO", VoiceSupport.chooseLanguage("ro-RO", listOf("en-US"), listOf("ro-RO")))
        assertEquals("en-US", VoiceSupport.chooseLanguage("en-GB", listOf("en-US", "de-DE"), emptyList()))
        assertEquals("fr-FR", VoiceSupport.chooseLanguage("fr-BE", listOf("en-US"), listOf("fr-FR")))
        assertEquals("xx-YY", VoiceSupport.chooseLanguage("xx-YY", listOf("en-US"), emptyList()))
        assertEquals(LanguageStatus.INSTALLED, VoiceSupport.status("en-US", listOf("en-us"), emptyList(), emptyList()))
        assertEquals(LanguageStatus.DOWNLOADING, VoiceSupport.status("de-DE", emptyList(), listOf("de-DE"), listOf("de-DE")))
        assertEquals(LanguageStatus.UNSUPPORTED, VoiceSupport.status("xx-YY", listOf("en-US"), emptyList(), emptyList()))
    }
}
