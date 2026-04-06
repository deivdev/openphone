package com.openphone.agent.agent

import android.util.Log
import com.openphone.agent.accessibility.PhoneControlService
import org.json.JSONObject

private const val TAG = "AgentAction"

data class AgentAction(
    val type: String,
    val x: Int = 0,
    val y: Int = 0,
    val text: String = "",
    val key: String = "",
    val app: String = "",
    val reason: String = ""
)

object ActionExecutor {

    // Same package map as agent.py
    private val commonApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "clock" to "com.google.android.deskclock",
        "camera" to "com.nothing.camera",
        "settings" to "com.android.settings",
        "phone" to "com.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "youtube" to "com.google.android.youtube",
        "calendar" to "com.google.android.calendar",
        "contacts" to "com.google.android.contacts",
        "calculator" to "com.google.android.calculator",
        "photos" to "com.google.android.apps.photos",
        "telegram" to "org.telegram.messenger",
    )

    fun parseAction(response: String): AgentAction? {
        Log.d(TAG, "parseAction input: ${response.take(500)}")
        // Strip <think> tags (some models emit these)
        val cleaned = response.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")

        // Find JSON blocks
        val jsonPattern = Regex("\\{[^{}]+\\}")
        for (match in jsonPattern.findAll(cleaned)) {
            try {
                val json = JSONObject(match.value)
                if (json.has("action")) {
                    val action = AgentAction(
                        type = json.getString("action"),
                        x = json.optInt("x", 0),
                        y = json.optInt("y", 0),
                        text = json.optString("text", ""),
                        key = json.optString("key", ""),
                        app = json.optString("app", ""),
                        reason = json.optString("reason", "")
                    )
                    Log.i(TAG, "Parsed action: ${action.type} (${action.x},${action.y}) ${action.app} ${action.reason}")
                    return action
                }
            } catch (_: Exception) {
                continue
            }
        }
        Log.w(TAG, "Failed to parse any action from response")
        return null
    }

    fun execute(action: AgentAction, service: PhoneControlService): String {
        Log.i(TAG, "Executing: ${action.type}")
        return when (action.type) {
            "tap" -> {
                service.performTap(action.x, action.y)
                "Tapped (${action.x},${action.y}): ${action.reason}"
            }
            "type" -> {
                service.typeText(action.text)
                "Typed \"${action.text}\": ${action.reason}"
            }
            "swipe_up" -> {
                val (w, h) = service.getScreenSize()
                service.performSwipe(w / 2, h * 3 / 4, w / 2, h / 4)
                "Swiped up: ${action.reason}"
            }
            "swipe_down" -> {
                val (w, h) = service.getScreenSize()
                service.performSwipe(w / 2, h / 4, w / 2, h * 3 / 4)
                "Swiped down: ${action.reason}"
            }
            "key" -> {
                when (action.key.lowercase()) {
                    "home" -> service.pressHome()
                    "back" -> service.pressBack()
                    "recent" -> service.pressRecents()
                    else -> false
                }
                "Pressed ${action.key}: ${action.reason}"
            }
            "open_app" -> {
                val pkg = commonApps[action.app.lowercase()] ?: action.app
                service.openApp(pkg)
                "Opened ${action.app}: ${action.reason}"
            }
            else -> "Unknown action: ${action.type}"
        }
    }
}
