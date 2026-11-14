package com.janos.voice

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Spoken(val lang: String, val speak: String)
data class ReplyText(val script: String, val roman: String)

/** Talks to the Gemini API (free tier key from aistudio.google.com). */
class Gemini(private val prefs: Prefs) {

    private val styleNote = """
Roman Pashto rules: write it the casual way Pashtuns in Peshawar/Khyber Pakhtunkhwa text on WhatsApp
(examples: "sanga ye", "sa kawe", "za dar razam", "khair de", "manana", "der kha", "ror", "kor ta").
Peshawar (Yousafzai/Northern) dialect. Keep English words (meeting, order, ok) as they are.
""".trimIndent()

    fun prepareIncoming(app: String, sender: String, text: String): Spoken {
        val corr = prefs.corrections.trim()
        val prompt = """
You are the voice of a phone assistant for a Pashtun user in Peshawar. A new notification arrived.
App: $app
From: $sender
Message: $text

1) Detect the message language: "ps" (Pashto, including Roman Pashto written in English letters),
   "ur" (Urdu or Roman Urdu), "en" (English) or "other".
2) Write ONE short sentence to be read aloud to the user that says who sent it and the full message.
   - If lang is "ps": write it entirely in Pashto script (Arabic letters), converting any Roman Pashto
     into correct Pashto spelling, e.g. "د علي پیغام دی: څنګه یې؟". Write the sender name in Pashto letters.
   - If lang is "ur": write it in Urdu script, e.g. "علی کا پیغام: ...".
   - If lang is "en" or "other": write it in simple English, e.g. "Message from Ali: ...".
   - For emails, say the sender and subject, then a one-line summary.
   - Never add anything that is not in the message.
${if (corr.isNotEmpty()) "User's spelling notes (Roman => meaning):\n$corr" else ""}
Return JSON only: {"lang":"ps|ur|en|other","speak":"..."}
""".trimIndent()
        val o = JSONObject(callText(prompt))
        return Spoken(o.optString("lang", "en"), o.optString("speak", "$sender: $text"))
    }

    fun transcribeReply(audio: File, mime: String, incoming: String): ReplyText {
        val prompt = """
This audio is the user's spoken reply (mostly Pashto, maybe mixed with Urdu/English words) to this message:
"$incoming"
Transcribe exactly what the user said — do not answer it, do not add words.
Return JSON only: {"script":"<reply in Pashto script>","roman":"<same reply in Roman Pashto>"}
If the user spoke Urdu, "script" is Urdu script and "roman" is Roman Urdu. If English, both are English.
$styleNote
""".trimIndent()
        val b64 = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
        val o = JSONObject(call(parts))
        return ReplyText(o.optString("script"), o.optString("roman"))
    }

    fun draftReply(incoming: String, sender: String): ReplyText {
        val prompt = """
Write a short, polite, natural reply from the user to this message from $sender:
"$incoming"
Reply in the same language the message used (Roman Pashto if it was Roman Pashto). Max 20 words.
Return JSON only: {"script":"<reply in Pashto/Urdu script, or English>","roman":"<reply as it will be sent>"}
$styleNote
""".trimIndent()
        val o = JSONObject(callText(prompt))
        return ReplyText(o.optString("script"), o.optString("roman"))
    }

    fun toScript(roman: String): String {
        val prompt = """
Convert this Roman Pashto/Urdu text into Pashto (or Urdu) script so a text-to-speech voice can read it.
Text: "$roman"
Return JSON only: {"script":"..."}
""".trimIndent()
        return JSONObject(callText(prompt)).optString("script", roman)
    }

    private fun callText(prompt: String): String =
        call(JSONArray().put(JSONObject().put("text", prompt)))

    private fun call(parts: JSONArray): String {
        try {
            return callOnce(parts)
        } catch (e: java.net.SocketTimeoutException) {
            try {
                return callOnce(parts)
            } catch (e2: java.net.SocketTimeoutException) {
                throw RuntimeException("Time out: Gemini server tak internet nahi pahuncha. App ka Wi-Fi/Mobile data access, VPN ya Data Saver check karein.")
            }
        } catch (e: java.net.UnknownHostException) {
            throw RuntimeException("Internet nahi mila (DNS). Wi-Fi/data on hai? App ka network access check karein.")
        }
    }

    /** Quick check: can we reach Gemini and is the key valid? Returns a human-readable line. */
    fun ping(): String {
        val t0 = System.currentTimeMillis()
        return try {
            val con = URL("https://generativelanguage.googleapis.com/v1beta/models/" + prefs.geminiModel)
                .openConnection() as HttpURLConnection
            con.connectTimeout = 30000
            con.readTimeout = 30000
            con.setRequestProperty("x-goog-api-key", prefs.geminiKey)
            val code = con.responseCode
            val body = (if (code in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            val ms = System.currentTimeMillis() - t0
            when (code) {
                200 -> "✅ Gemini theek hai (${ms} ms), model: ${prefs.geminiModel}"
                400, 401, 403 -> "❌ Gemini key ghalat ya band hai ($code): ${body.take(200)}"
                404 -> "❌ Model naam ghalat hai (${prefs.geminiModel}). 'gemini-2.5-flash' likh kar dekhein."
                else -> "❌ Gemini error $code: ${body.take(200)}"
            }
        } catch (e: Exception) {
            "❌ Gemini tak connection nahi bana: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    private fun callOnce(parts: JSONArray): String {
        val key = prefs.geminiKey
        if (key.isEmpty()) throw IllegalStateException("Gemini API key settings mein daalein")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2))
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" +
                prefs.geminiModel + ":generateContent")
        val con = url.openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 30000
        con.readTimeout = 90000
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("x-goog-api-key", key)
        con.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = con.responseCode
        val txt = (if (code in 200..299) con.inputStream else con.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw RuntimeException("Gemini error $code: ${txt.take(300)}")
        val cand = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
        val ps = cand.getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return cleanJson(sb.toString())
    }

    private fun cleanJson(s: String): String {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        return if (a >= 0 && b > a) s.substring(a, b + 1) else "{}"
    }
}
