package com.openphone.agent.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class GroqBackend(
    private val apiKey: String,
    private val model: String = "llama-3.3-70b-versatile"
) : LlmBackend {

    private val stopped = AtomicBoolean(false)

    override fun isReady(): Boolean = apiKey.isNotBlank()

    override fun generate(systemPrompt: String, userMessage: String): String {
        stopped.set(false)

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0)
            put("max_tokens", 512)
        }

        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (stopped.get()) return "[Stopped]"

            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.let {
                    BufferedReader(InputStreamReader(it)).readText()
                } ?: "HTTP $code"
                return "[Error: $error]"
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val json = JSONObject(response)
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            return "[Error: ${e.message}]"
        } finally {
            conn.disconnect()
        }
    }

    override fun stop() {
        stopped.set(true)
    }

    override fun release() {}
}
