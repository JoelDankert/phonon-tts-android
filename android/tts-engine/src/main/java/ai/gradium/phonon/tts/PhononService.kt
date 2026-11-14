package ai.gradium.phonon.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phonon as a system text-to-speech engine. Android calls [onSynthesizeText] on one thread, one
 * request at a time, and [onStop] from another to cut the current one short.
 *
 * Android voices are named `<model-id>:<language>:<speaker>`: every checkpoint voice in every language the
 * checkpoint speaks.
 */
class PhononService : TextToSpeechService() {
    @Volatile private var stopped = false
    @Volatile private var language: String? = null

    override fun onCreate() {
        super.onCreate()
        val selected = Model.selected(this)
        language = Model.systemLanguage(this)?.takeIf { it in selected?.languages.orEmpty() }
            ?: selected?.languages?.firstOrNull()
    }

    // Answered from the installed files, without waiting for the model to load.
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val code = Model.code(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        // The checkpoints have no regional variants, so a country never matches more closely.
        return if (code in Model.selected(this)?.languages.orEmpty()) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_MISSING_DATA
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) language = Model.code(lang)
        return result
    }

    override fun onGetLanguage(): Array<String> {
        val available = Model.selected(this)?.languages.orEmpty()
        val code = language?.takeIf { it in available } ?: available.firstOrNull() ?: return arrayOf("", "", "")
        return arrayOf(Locale.forLanguageTag(code).isO3Language, "", "")
    }

    override fun onGetVoices(): List<Voice> {
        val entry = Model.selected(this) ?: return emptyList()
        return entry.languages.flatMap { lang ->
            BundledVoices.metadata(this, entry, lang).map { speaker -> voice(entry.id,lang,speaker) }
        }
    }
    override fun onIsValidVoiceName(voiceName: String?): Int = if(parse(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(voiceName: String?): Int {
        val choice = parse(voiceName) ?: return TextToSpeech.ERROR
        BundledVoices.select(this, choice.entry.id, choice.voiceLanguage, choice.speaker)
        return TextToSpeech.SUCCESS
    }
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val code = Model.code(lang) ?: return null
        val entry = Model.selected(this)?.takeIf { code in it.languages } ?: return null
        val selected = BundledVoices.selected(this, entry) ?: return null
        return Routing.voice(entry.id,selected.language,selected.speaker)
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val entry = Model.selected(this) ?: run { fail(callback,TextToSpeech.ERROR_INVALID_REQUEST); return }
        val lang = Model.code(request.language)?.takeIf { it in entry.languages }
            ?: run { fail(callback,TextToSpeech.ERROR_INVALID_REQUEST); return }
        val choice = BundledVoices.selected(this, entry)?.let {
            Choice(entry, it.language, it.speaker)
        } ?: run { fail(callback,TextToSpeech.ERROR_INVALID_REQUEST); return }
        val speaker = choice.speaker
        val text = TextReplacementRules.apply(this, request.charSequenceText?.toString().orEmpty())
        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        if (text.isBlank()) {
            callback.done()
            return
        }
        try {
            val result = Model.use(this,choice.entry) { model ->
                model.tts.setLanguage(lang)
                check(speaker in model.speakers) { "Selected voice is unavailable" }
                model.tts.setVoice(speaker)
                SentenceSpeech(
                    request.speechRate, callback.maxBufferSize, { stopped },
                    { sentence, pcm -> model.tts.speak(sentence, pcm) },
                    { bytes, n -> callback.audioAvailable(bytes, 0, n) == TextToSpeech.SUCCESS },
                    { frame, start, end ->
                        if (android.os.Build.VERSION.SDK_INT >= 26) callback.rangeStart(frame, start, end)
                    },
                ).speak(text, Locale.forLanguageTag(lang))
            }
            when (result) {
                SentenceSpeech.Result.COMPLETE -> if (!stopped) callback.done()
                SentenceSpeech.Result.OUTPUT_ERROR -> if (!stopped) fail(callback, TextToSpeech.ERROR_OUTPUT)
                SentenceSpeech.Result.STOPPED -> Unit
                null -> if (!stopped) fail(callback, TextToSpeech.ERROR_SYNTHESIS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            if (!stopped) fail(callback, TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    /** Android only tells the app about an error once the request is done. */
    private fun fail(callback: SynthesisCallback, code: Int) {
        callback.error(code)
        callback.done()
    }

    private fun voice(id: String, lang: String, speaker: String) = Voice(
        Routing.voice(id,lang,speaker), Locale.forLanguageTag(lang), Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false, emptySet())
    private data class Choice(val entry: Model.Entry, val voiceLanguage: String, val speaker: String)
    private fun parse(name: String?): Choice? {
        if(name == null) return null
        val entry = Model.selected(this) ?: return null
        val voices = entry.languages.flatMap { lang ->
            BundledVoices.metadata(this,entry,lang).map { VoiceIdentity(entry.id,lang,it,BundledVoices.androidName(lang,it)) }
        }
        val found = Routing.resolve(name,voices,emptyMap()) ?: return null
        return Choice(entry,found.lang,found.speaker)
    }

    private companion object {
        const val TAG = "PhononService"

        /** What ptts.h hands out: 24 kHz mono. */
        const val SAMPLE_RATE = 24000

        /** The voice name's speaker part for a checkpoint without voices. */
        const val NO_SPEAKER = "default"

        /** Floats in [-1, 1] to little-endian 16-bit PCM. */
        fun pcm16(pcm: FloatArray): ByteArray {
            val out = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
            return out
        }
    }
}
