package com.janos.voice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusBox: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var resultBox: TextView
    private val bg = Executors.newSingleThreadExecutor()
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Speaker.init(this)
        Notifier.ensureChannel(this)
        pad = (14 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }

        root.addView(h("JanOS Voice"))
        root.addView(p("Aapke messages bol kar sunata hai aur aap ki awaaz se Roman Pashto mein jawab bhejta hai."))

        statusBox = p("")
        root.addView(statusBox)
        root.addView(btn("1) Notification Access do") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(btn("2) Mic + Notification permission do") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS), 3)
        })
        root.addView(btn("3) Battery se band na ho (zaroori)") { askBattery() })
        root.addView(p("Infinix par: Settings > Apps > JanOS Voice > Battery > 'Unrestricted', aur Settings > Apps > JanOS Voice mein Wi-Fi/Mobile data access ON rakhein. Recent apps mein app ko lock bhi kar dein."))

        root.addView(h("Recent messages"))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)
        root.addView(btn("🔊 Sab messages sunao") { readAll() })

        root.addView(h("Settings"))
        val auto = Switch(this).apply {
            text = "Naya message aate hi bol kar sunao"
            isChecked = prefs.autoRead
            setOnCheckedChangeListener { _, v -> prefs.autoRead = v }
        }
        root.addView(auto)

        val gKey = field("Gemini API key (free: aistudio.google.com/apikey)", prefs.geminiKey, true)
        val gModel = field("Gemini model", prefs.geminiModel, false)
        val eKey = field("ElevenLabs API key (Pashto awaaz)", prefs.elevenKey, true)
        val eVoice = field("ElevenLabs Voice ID (aapki cloned voice)", prefs.elevenVoiceId, false)
        val eModel = field("ElevenLabs model", prefs.elevenModel, false)
        listOf(gKey, gModel, eKey, eVoice, eModel).forEach { root.addView(it) }

        root.addView(p("Roman spelling notes (har line: likha hua => matlab), jaise:  skye => sanga ye"))
        val corr = EditText(this).apply { setText(prefs.corrections); minLines = 3 }
        root.addView(corr)

        root.addView(h("Kin apps ke messages sunaun?"))
        val boxes = mutableMapOf<String, CheckBox>()
        val enabled = prefs.enabledApps
        Prefs.DEFAULT_APPS.forEach { (pkg, name) ->
            val cb = CheckBox(this).apply { text = name; isChecked = pkg in enabled }
            boxes[pkg] = cb
            root.addView(cb)
        }

        root.addView(btn("💾 Save") {
            prefs.geminiKey = gKey.text.toString()
            prefs.geminiModel = gModel.text.toString()
            prefs.elevenKey = eKey.text.toString()
            prefs.elevenVoiceId = eVoice.text.toString()
            prefs.elevenModel = eModel.text.toString()
            prefs.corrections = corr.text.toString()
            prefs.enabledApps = boxes.filter { it.value.isChecked }.keys
            Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show()
        })
        root.addView(btn("🧪 Test: Roman Pashto message sunao") { testVoice() })
        root.addView(btn("🌐 Connection check") { checkConnection() })
        resultBox = p("")
        root.addView(resultBox)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val listenerOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, NotifListener::class.java).flattenToString()) == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        fun m(b: Boolean) = if (b) "✅" else "❌"
        statusBox.text = "${m(listenerOn)} Notification access   ${m(mic)} Mic   ${m(battery)} Battery\n" +
                "${m(prefs.geminiKey.isNotEmpty())} Gemini key   ${m(prefs.elevenKey.isNotEmpty() && prefs.elevenVoiceId.isNotEmpty())} ElevenLabs"

        recentBox.removeAllViews()
        val list = MessageStore.all()
        if (list.isEmpty()) recentBox.addView(p("Abhi koi message nahi aaya."))
        list.take(15).forEach { c ->
            recentBox.addView(Button(this).apply {
                isAllCaps = false
                text = "${c.sender} (${c.appName}): ${c.text.take(60)}"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ReplyActivity::class.java).putExtra("id", c.id))
                }
            })
        }
    }

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun readAll() {
        val list = MessageStore.all().take(10).reversed()
        if (list.isEmpty()) { Speaker.speak(this, "en", "No new messages"); return }
        bg.execute {
            list.forEach { c ->
                val s = try { Gemini(prefs).prepareIncoming(c.appName, c.sender, c.text) }
                catch (_: Exception) { Spoken("other", "${c.sender}: ${c.text}") }
                Speaker.speak(this, s.lang, s.speak)
            }
        }
    }

    private fun show(msg: String) = runOnUiThread { resultBox.text = msg }

    private fun testVoice() {
        show("Test chal raha hai… (10-20 second lag sakte hain)")
        bg.execute {
            try {
                val t0 = System.currentTimeMillis()
                val s = Gemini(prefs).prepareIncoming("WhatsApp", "Ali", "sanga ye ror? sabaa kor ta rasha")
                show("Gemini ne ${(System.currentTimeMillis() - t0) / 1000}s mein jawab diya [${s.lang}]:\n${s.speak}\nAb bol raha hai…")
                Speaker.speak(this, s.lang, s.speak) { err ->
                    show(if (err != null) "Awaaz ka masla: $err" else "✅ Test mukammal:\n${s.speak}")
                }
            } catch (e: Exception) {
                show("❌ Masla: ${e.message}")
            }
        }
    }

    private fun checkConnection() {
        show("Check kar raha hai…")
        bg.execute { show(Gemini(prefs).ping()) }
    }

    private fun h(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 3)
    }

    private fun p(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, 4, 0, 4) }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { f() }
    }

    private fun field(hintText: String, value: String, secret: Boolean) = EditText(this).apply {
        hint = hintText
        setText(value)
        isSingleLine = true
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
}
