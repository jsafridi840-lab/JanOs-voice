package com.janos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Speaks text one item at a time.
 * Pashto -> ElevenLabs (cloned voice) if a key + voice id are set, else the phone's own voice.
 * Urdu / English -> the phone's free Google voice.
 */
object Speaker {
    private const val TAG = "JanOSSpeaker"
    private val queue = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private val ttsReady = CountDownLatch(1)
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (tts != null) return
        app = ctx.applicationContext
        tts = TextToSpeech(app) { ttsReady.countDown() }
    }

    fun speak(ctx: Context, lang: String, text: String, onDone: ((String?) -> Unit)? = null) {
        init(ctx)
        queue.execute {
            var err: String? = null
            withFocus {
                try {
                    val p = Prefs(app)
                    if (lang == "ps" && p.elevenKey.isNotEmpty() && p.elevenVoiceId.isNotEmpty()) {
                        try {
                            playEleven(p, text)
                        } catch (e: Exception) {
                            Log.w(TAG, "ElevenLabs failed", e)
                            err = "ElevenLabs: ${e.message}"
                            speakSystem(lang, text)
                        }
                    } else {
                        speakSystem(lang, text)
                    }
                } catch (e: Exception) {
                    err = e.message
                }
            }
            onDone?.invoke(err)
        }
    }

    fun stop() {
        tts?.stop()
    }

    private fun withFocus(block: () -> Unit) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs()).build()
        am.requestAudioFocus(req)
        try { block() } finally { am.abandonAudioFocusRequest(req) }
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun speakSystem(lang: String, text: String) {
        ttsReady.await(10, TimeUnit.SECONDS)
        val t = tts ?: return
        val wanted = when (lang) {
            "ps" -> listOf(Locale("ps", "AF"), Locale("ps"), Locale("ur", "PK"))
            "ur" -> listOf(Locale("ur", "PK"), Locale("ur"), Locale("hi", "IN"))
            else -> listOf(Locale.US, Locale.UK)
        }
        for (l in wanted) {
            val r = t.setLanguage(l)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break
        }
        t.setAudioAttributes(attrs())
        val done = CountDownLatch(1)
        val id = "u" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.countDown() }
            @Deprecated("old api")
            override fun onError(utteranceId: String?) { done.countDown() }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        done.await(60, TimeUnit.SECONDS)
    }

    private fun playEleven(p: Prefs, text: String) {
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/${p.elevenVoiceId}?output_format=mp3_44100_64")
        val con = url.openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 15000
        con.readTimeout = 45000
        con.doOutput = true
        con.setRequestProperty("xi-api-key", p.elevenKey)
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("Accept", "audio/mpeg")
        val body = JSONObject().put("text", text).put("model_id", p.elevenModel)
        con.outputStream.use { it.write(body.toString().toByteArray()) }
        if (con.responseCode !in 200..299) {
            val e = con.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("${con.responseCode} ${e.take(200)}")
        }
        val f = File(app.cacheDir, "say.mp3")
        con.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        mp.setAudioAttributes(attrs())
        mp.setDataSource(f.absolutePath)
        mp.setOnCompletionListener { done.countDown() }
        mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
        mp.prepare()
        mp.start()
        done.await(90, TimeUnit.SECONDS)
        mp.release()
    }
}
