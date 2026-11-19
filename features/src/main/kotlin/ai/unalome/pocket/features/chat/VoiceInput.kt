package ai.unalome.pocket.features.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Whether dictation can run on this phone, and if not, why. */
sealed interface VoiceAvailability {
    /** On-device recognition for [languageTag] is ready. */
    data class Available(val languageTag: String) : VoiceAvailability

    /** The language can be recognised on the phone once Android downloads it (Android 13+). */
    data class NeedsDownload(val languageTag: String, val languageName: String) : VoiceAvailability

    data class Unavailable(val reason: String) : VoiceAvailability
}

/**
 * The rules for voice input, apart from the Android calls so they are tested. Speech is only ever
 * recognised on the phone: there is no fall-back to a recogniser that sends audio to a server.
 */
object VoiceSupport {
    /** What Android reports for the chosen language, from `checkRecognitionSupport` (Android 13+). */
    enum class LanguageStatus { INSTALLED, DOWNLOADING, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

    fun decide(sdk: Int, onDeviceAvailable: Boolean, languageTag: String, languageName: String, status: LanguageStatus): VoiceAvailability =
        when {
            sdk < Build.VERSION_CODES.S -> VoiceAvailability.Unavailable(
                "Voice input needs Android 12 or later, which can recognise speech on the phone itself. Nothing is sent to a server instead.",
            )
            !onDeviceAvailable -> VoiceAvailability.Unavailable(
                "This phone has no on-device speech recognition, so voice input is off. Nothing is sent to a server instead.",
            )
            status == LanguageStatus.INSTALLED || status == LanguageStatus.UNKNOWN -> VoiceAvailability.Available(languageTag)
            status == LanguageStatus.DOWNLOADING -> VoiceAvailability.Unavailable(
                "On-device speech recognition for $languageName is still downloading. Try again in a few minutes.",
            )
            status == LanguageStatus.DOWNLOADABLE && sdk >= Build.VERSION_CODES.TIRAMISU ->
                VoiceAvailability.NeedsDownload(languageTag, languageName)
            else -> VoiceAvailability.Unavailable(
                "On-device speech recognition for $languageName is not installed on this phone, so voice input is off. " +
                    "Nothing is sent to a server instead.",
            )
        }

    /**
     * The language to listen for: the phone's language if the phone can recognise it; else the
     * same language in another region it has (en-GB for en-US); else the phone's language as is,
     * so the answer names it.
     */
    fun chooseLanguage(deviceTag: String, installed: List<String>, downloadable: List<String>): String {
        fun language(tag: String) = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        val all = installed + downloadable
        all.firstOrNull { it.equals(deviceTag, ignoreCase = true) }?.let { return it }
        installed.firstOrNull { language(it) == language(deviceTag) }?.let { return it }
        downloadable.firstOrNull { language(it) == language(deviceTag) }?.let { return it }
        return deviceTag
    }

    fun status(tag: String, installed: List<String>, pending: List<String>, downloadable: List<String>): LanguageStatus = when {
        installed.any { it.equals(tag, ignoreCase = true) } -> LanguageStatus.INSTALLED
        pending.any { it.equals(tag, ignoreCase = true) } -> LanguageStatus.DOWNLOADING
        downloadable.any { it.equals(tag, ignoreCase = true) } -> LanguageStatus.DOWNLOADABLE
        else -> LanguageStatus.UNSUPPORTED
    }
}

/**
 * Dictation into the composer with Android's on-device recogniser. Main thread only.
 * Partial results stream in while listening; tapping the microphone again stops. Nothing is sent.
 */
class VoiceInput(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Listening : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    /** Checks what the phone can do for its current language. */
    suspend fun availability(): VoiceAvailability {
        val deviceTag = Locale.getDefault().toLanguageTag()
        val name = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifEmpty { deviceTag }
        val unknown = VoiceSupport.LanguageStatus.UNKNOWN
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return VoiceSupport.decide(Build.VERSION.SDK_INT, false, deviceTag, name, unknown)
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return VoiceSupport.decide(Build.VERSION.SDK_INT, onDevice, deviceTag, name, unknown)
        }
        return languageAvailability(deviceTag, name)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun languageAvailability(deviceTag: String, name: String): VoiceAvailability {
        val sdk = Build.VERSION.SDK_INT
        val support = withTimeoutOrNull(3000) { recognitionSupport(deviceTag) }
            ?: return VoiceSupport.decide(sdk, true, deviceTag, name, VoiceSupport.LanguageStatus.UNKNOWN)
        val installed = support.installedOnDeviceLanguages
        val pending = support.pendingOnDeviceLanguages
        val downloadable = support.supportedOnDeviceLanguages
        val tag = VoiceSupport.chooseLanguage(deviceTag, installed, downloadable)
        val tagName = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifEmpty { name }
        return VoiceSupport.decide(sdk, true, tag, tagName, VoiceSupport.status(tag, installed, pending, downloadable))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognitionSupport(tag: String): RecognitionSupport? = suspendCancellableCoroutine { continuation ->
        val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        continuation.invokeOnCancellation { checker.destroy() }
        checker.checkRecognitionSupport(
            intent(tag),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    checker.destroy()
                    if (continuation.isActive) continuation.resume(recognitionSupport)
                }

                override fun onError(error: Int) {
                    checker.destroy()
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    /** Asks Android to download on-device recognition for [tag]; Android shows its own progress. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun downloadLanguage(tag: String, onResult: (String?) -> Unit) {
        val downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        if (Build.VERSION.SDK_INT >= 34) {
            downloader.triggerModelDownload(
                intent(tag),
                context.mainExecutor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {}
                    override fun onSuccess() { downloader.destroy(); onResult(null) }
                    override fun onScheduled() { downloader.destroy(); onResult("The download is scheduled; Android finishes it on Wi-Fi.") }
                    override fun onError(error: Int) { downloader.destroy(); onResult("Android could not download the speech model ($error).") }
                },
            )
        } else {
            downloader.triggerModelDownload(intent(tag))
            downloader.destroy()
            onResult("Android is downloading the speech model. Try the microphone again when it is done.")
        }
    }

    /**
     * Starts listening. [onText] gets the words so far (partial, then final); [onEnd] runs once
     * with an error message, or `null` when listening ended normally.
     */
    fun start(languageTag: String, onText: (String) -> Unit, onEnd: (String?) -> Unit) {
        if (_state.value == State.Listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = created
        var ended = false
        fun finish(message: String?) {
            if (ended) return
            ended = true
            _state.value = State.Idle
            created.destroy()
            if (recognizer === created) recognizer = null
            onEnd(message)
        }
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(onText)
            }
            override fun onResults(results: Bundle?) {
                best(results)?.let(onText)
                finish(null)
            }
            override fun onError(error: Int) {
                finish(errorMessage(error))
            }
        })
        _state.value = State.Listening
        created.startListening(intent(languageTag))
    }

    /** Stops listening; what was heard so far is kept. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        _state.value = State.Idle
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun errorMessage(error: Int): String? = when (error) {
        // Silence or nothing recognised just ends listening.
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Voice input needs microphone access."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "On-device speech recognition for this language is not installed on this phone, so voice input is off. Nothing is sent to a server instead."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recogniser is busy. Try again in a moment."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        else -> "Voice input stopped (error $error)."
    }
}
