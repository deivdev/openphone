package com.openphone.agent.agent

import com.openphone.agent.accessibility.PhoneControlService
import com.openphone.agent.llm.LlmBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class StepInfo(
    val step: Int,
    val maxSteps: Int,
    val status: String,
    val isTerminal: Boolean = false
)

class AgentLoop(
    private var llm: LlmBackend,
    private val onStep: (StepInfo) -> Unit
) {
    fun setBackend(backend: LlmBackend) {
        llm = backend
    }

    private val maxSteps = 20
    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    suspend fun run(goal: String) = withContext(Dispatchers.Default) {
        running = true
        val history = mutableListOf<String>()

        for (step in 1..maxSteps) {
            if (!running) break

            val service = PhoneControlService.instance
            if (service == null) {
                onStep(StepInfo(step, maxSteps, "Accessibility Service not enabled", isTerminal = true))
                break
            }

            // 1. Read screen
            onStep(StepInfo(step, maxSteps, "Reading screen..."))
            val uiTree = service.getUiTree()

            // 2. Build prompt
            val (systemPrompt, userMessage) = buildPrompt(goal, uiTree, history.takeLast(5))

            // 3. Ask LLM
            onStep(StepInfo(step, maxSteps, "Thinking... (${uiTree.lines().size} elements)"))
            val response = llm.generate(systemPrompt, userMessage)

            // Log the raw response for debugging
            val preview = response.take(200).replace("\n", " ")
            onStep(StepInfo(step, maxSteps, "LLM: $preview"))

            // 4. Parse action
            val action = ActionExecutor.parseAction(response)
            if (action == null) {
                onStep(StepInfo(step, maxSteps, "Failed to parse: ${response.take(300)}"))
                history.add("Failed to parse action")
                delay(1000)
                continue
            }

            // 5. Terminal states
            if (action.type == "done") {
                onStep(StepInfo(step, maxSteps, "Done: ${action.reason}", isTerminal = true))
                break
            }
            if (action.type == "fail") {
                onStep(StepInfo(step, maxSteps, "Failed: ${action.reason}", isTerminal = true))
                break
            }

            // 6. Execute
            onStep(StepInfo(step, maxSteps, "${action.type}: ${action.reason}"))
            val desc = ActionExecutor.execute(action, service)
            history.add(desc)

            // 7. Wait for UI to settle
            delay(1500)
        }

        if (running) {
            onStep(StepInfo(maxSteps, maxSteps, "Max steps reached", isTerminal = true))
        }
        running = false
    }

    fun stop() {
        running = false
        llm.stop()
    }

    private fun buildPrompt(goal: String, uiTree: String, history: List<String>): Pair<String, String> {
        val systemPrompt = """You are a phone automation agent. You control an Android phone to accomplish tasks.

GOAL: $goal

You see a list of UI elements currently on screen. Each element has:
- [index] WidgetType "label" (resource_id) [properties] @ (tap_x, tap_y)

Choose ONE action. Respond with ONLY a JSON object:

{"action": "tap", "x": <int>, "y": <int>, "reason": "..."}
{"action": "type", "text": "...", "reason": "..."}
{"action": "swipe_up", "reason": "..."}
{"action": "swipe_down", "reason": "..."}
{"action": "key", "key": "home|back|enter", "reason": "..."}
{"action": "open_app", "app": "<app_name>", "reason": "..."}
{"action": "done", "reason": "..."}
{"action": "fail", "reason": "..."}

Rules:
- Use "open_app" to launch apps (whatsapp, chrome, clock, etc.)
- Use tap coordinates from the element list (the @ values)
- Type text only when an input field is focused
- Press "back" to go back, "home" for home screen
- Say "done" when the goal is achieved
- Say "fail" if impossible

Respond with ONLY the JSON, no other text."""

        val historyText = if (history.isNotEmpty()) {
            "\nPrevious actions:\n" + history.joinToString("\n")
        } else ""

        val userMessage = "Current screen elements:\n$uiTree$historyText\n\nWhat is the next action?"

        return Pair(systemPrompt, userMessage)
    }
}
