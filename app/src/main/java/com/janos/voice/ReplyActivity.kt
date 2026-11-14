package com.janos.voice

import android.Manifest
import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class ReplyActivity : Activity() {

    private var convo: Convo? = null
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var lastScript = ""
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var micBtn: Button
    private lateinit var input: EditText
    private lateinit var sendBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.init(this)
        convo = MessageStore.byId(intent.getIntExtra("id", -1))
        val c = convo
        if (c == null) {
            Toast.makeText(this, "Yeh message ab available nahi", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        Notifier.cancel(this, c.id)
        title = "${c.sender} • ${c.appName}"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = c.text; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)

        micBtn = Button(this).apply { textSize = 18f; setOnClickListener { toggleMic() } }
        root.addView(micBtn)

        input = EditText(this).apply {
            hint = "Jawab (Roman Pashto) — yahan theek bhi kar sakte hain"
            minLines = 2
        }
        root.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rb(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(rb("🔊 Suno") { replayReply() })
        row.addView(rb("🤖 AI jawab") { aiDraft() })
        root.addView(row)

        sendBtn = Button(this).apply {
            text = "✅ Bhejo"; textSize = 18f; setOnClickListener { send() }
        }
        root.addView(sendBtn)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(rb("🔁 Message dobara suno") { rereadIncoming() })
        row2.addView(rb("📱 App kholo") { openApp() })
        root.addView(row2)
        root.addView(Button(this).apply { text = "Band karo"; setOnClickListener { finish() } })

        setContentView(root)

        if (c.replyAction == null) {
            sendBtn.visibility = View.GONE
            micBtn.visibility = View.GONE
            status.text = "Is notification mein seedha jawab ka option nahi (jaise Gmail). App khol kar jawab dein."
        } else {
            micBtn.text = "🎤 Bolo"
            if (hasMic()) startRec() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 7 && hasMic()) startRec()
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleMic() {
        if (recorder != null) stopRecAndTranscribe()
        else if (hasMic()) startRec()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    @Suppress("DEPRECATION")
    private fun startRec() {
        Speaker.stop()
        val f = File(cacheDir, "reply.aac")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            status.text = "Mic start nahi hua: ${e.message}"
            return
        }
        recorder = r
        recFile = f
        micBtn.text = "⏹ Bas, ruk jao"
        status.text = "Sun raha hoon… Pashto mein jawab bolein, phir ⏹ dabayein"
        ui.postDelayed({ if (recorder === r) stopRecAndTranscribe() }, 30_000)
    }

    private fun stopRecAndTranscribe() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (_: Exception) {}
        r.release()
        micBtn.text = "🎤 Dobara bolo"
        val f = recFile ?: return
        if (f.length() < 1500) { status.text = "Awaaz nahi aayi, dobara bolein"; return }
        status.text = "Samajh raha hoon…"
        val c = convo ?: return
        bg.execute {
            try {
                val res = Gemini(Prefs(this)).transcribeReply(f, "audio/aac", c.text)
                ui.post {
                    input.setText(res.roman)
                    lastScript = res.script
                    status.text = "Theek hai to ✅ Bhejo dabayein. Ghalat ho to text theek karein ya dobara bolein."
                }
                Speaker.speak(this, "ps", res.script)
            } catch (e: Exception) {
                ui.post { status.text = "Masla: ${e.message}" }
            }
        }
    }

    private fun aiDraft() {
        val c = convo ?: return
        status.text = "AI jawab bana raha hai…"
        bg.execute {
            try {
                val res = Gemini(Prefs(this)).draftReply(c.text, c.sender)
                ui.post {
                    input.setText(res.roman); lastScript = res.script
                    status.text = "AI ka jawab. Pasand ho to ✅ Bhejo."
                }
                Speaker.speak(this, "ps", res.script)
            } catch (e: Exception) {
                ui.post { status.text = "Masla: ${e.message}" }
            }
        }
    }

    private fun replayReply() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        bg.execute {
            val script = try { Gemini(Prefs(this)).toScript(t) } catch (_: Exception) { t }
            Speaker.speak(this, "ps", script)
        }
    }

    private fun rereadIncoming() {
        val c = convo ?: return
        bg.execute {
            val s = try {
                Gemini(Prefs(this)).prepareIncoming(c.appName, c.sender, c.text)
            } catch (_: Exception) { Spoken("other", "${c.sender}: ${c.text}") }
            Speaker.speak(this, s.lang, s.speak)
        }
    }

    private fun send() {
        val c = convo ?: return
        val a = c.replyAction ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { status.text = "Pehle jawab bolein ya likhein"; return }
        try {
            val i = Intent()
            val b = Bundle()
            a.remoteInputs.forEach { b.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(a.remoteInputs, i, b)
            a.actionIntent.send(this, 0, i)
            Toast.makeText(this, "Bhej diya ✅", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            status.text = "Nahi bheja ja saka (notification purani ho gayi). App khol kar bhejein."
        }
    }

    private fun openApp() {
        val c = convo ?: return
        try {
            if (c.openIntent != null) c.openIntent.send()
            else packageManager.getLaunchIntentForPackage(c.pkg)?.let { startActivity(it) }
        } catch (_: Exception) {
            packageManager.getLaunchIntentForPackage(c.pkg)?.let { startActivity(it) }
        }
        finish()
    }

    override fun onDestroy() {
        recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        recorder = null
        super.onDestroy()
    }
}
