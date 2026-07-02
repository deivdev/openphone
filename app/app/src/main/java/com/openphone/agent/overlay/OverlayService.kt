package com.openphone.agent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.openphone.agent.R
import com.openphone.agent.agent.AgentLoop
import com.openphone.agent.agent.ActionExecutor
import com.openphone.agent.llm.GroqBackend
import com.openphone.agent.llm.LlmBackend
import com.openphone.agent.llm.LocalLlmBackend
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "openphone_overlay"

        var instance: OverlayService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var inputGoal: EditText
    private lateinit var btnRun: Button
    private lateinit var btnStop: Button
    private lateinit var txtLog: TextView
    private lateinit var contentLayout: LinearLayout

    private var backend: LlmBackend? = null
    private var agentLoop: AgentLoop? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isCollapsed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupOverlay()
        setupBackend()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        agentLoop?.stop()
        try {
            windowManager.removeView(overlayView)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "OpenPhone Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenPhone Agent")
            .setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    private fun setupBackend() {
        val prefs = getSharedPreferences("openphone", Context.MODE_PRIVATE)
        val mode = prefs.getString("llm_mode", "LOCAL")

        backend = when (mode) {
            "GROQ" -> {
                val key = prefs.getString("groq_api_key", "") ?: ""
                val model = prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
                if (key.isNotBlank()) GroqBackend(key, model) else null
            }
            else -> {
                val path = prefs.getString("model_path", null)
                if (path != null && java.io.File(path).exists()) {
                    val local = LocalLlmBackend()
                    scope.launch(Dispatchers.IO) {
                        addLog("Loading model...")
                        val ok = local.loadModel(path, 4, 4096)
                        if (ok) addLog("Model ready")
                        else addLog("Failed to load model")
                    }
                    local
                } else null
            }
        }

        if (backend == null) {
            addLog("No LLM configured. Set up in main app first.")
        }

        agentLoop = backend?.let { b ->
            AgentLoop(b) { step ->
                scope.launch { addLog("[${step.step}/${step.maxSteps}] ${step.status}") }
            }
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        inputGoal = overlayView.findViewById(R.id.input_goal)
        btnRun = overlayView.findViewById(R.id.btn_run)
        btnStop = overlayView.findViewById(R.id.btn_stop)
        txtLog = overlayView.findViewById(R.id.txt_log)
        contentLayout = overlayView.findViewById(R.id.overlay_content)

        val header = overlayView.findViewById<LinearLayout>(R.id.overlay_header)
        val btnCollapse = overlayView.findViewById<Button>(R.id.btn_collapse)
        val btnClose = overlayView.findViewById<Button>(R.id.btn_close)

        // Drag to move
        var initialY = 0
        var touchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        btnCollapse.setOnClickListener {
            isCollapsed = !isCollapsed
            contentLayout.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            btnCollapse.text = if (isCollapsed) "+" else "—"
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        btnRun.setOnClickListener {
            val goal = inputGoal.text.toString().trim()
            if (goal.isBlank() || agentLoop == null) return@setOnClickListener

            btnRun.isEnabled = false
            btnStop.isEnabled = true
            txtLog.text = ""

            // Minimize and go home so the agent can see other apps
            isCollapsed = true
            contentLayout.visibility = View.GONE
            val collapseBtn = overlayView.findViewById<Button>(R.id.btn_collapse)
            collapseBtn.text = "+"

            scope.launch {
                addLog("Goal: $goal")
                try {
                    agentLoop?.run(goal)
                } catch (e: Exception) {
                    addLog("Error: ${e.message}")
                }
                btnRun.isEnabled = true
                btnStop.isEnabled = false

                // Expand to show results
                isCollapsed = false
                contentLayout.visibility = View.VISIBLE
                collapseBtn.text = "—"
            }
        }

        btnStop.setOnClickListener {
            agentLoop?.stop()
            addLog("Stopped")
            btnRun.isEnabled = true
            btnStop.isEnabled = false
        }

        windowManager.addView(overlayView, params)
    }

    private fun addLog(msg: String) {
        android.util.Log.i("OpenPhoneAgent", msg)
        scope.launch(Dispatchers.Main) {
            val current = txtLog.text.toString()
            val updated = if (current.isEmpty()) msg else "$current\n$msg"
            // Keep last 20 lines
            val lines = updated.lines()
            txtLog.text = if (lines.size > 20) lines.takeLast(20).joinToString("\n") else updated
        }
    }
}
