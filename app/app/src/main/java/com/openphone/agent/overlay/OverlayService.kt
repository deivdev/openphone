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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.openphone.agent.OpenPhoneApp
import com.openphone.agent.R
import com.openphone.agent.agent.AgentLoop
import com.openphone.agent.llm.ModelState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

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
    private lateinit var btnRun: TextView
    private lateinit var btnStop: TextView
    private lateinit var btnCollapse: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtStatus: TextView
    private lateinit var statusDot: View
    private lateinit var contentLayout: LinearLayout

    private val modelManager by lazy { (application as OpenPhoneApp).modelManager }
    private lateinit var agentLoop: AgentLoop
    private var agentRunning = false
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
        if (::agentLoop.isInitialized) agentLoop.stop()
        try {
            windowManager.removeView(overlayView)
        } catch (_: Exception) {}
        // The overlay was the model's only user; free its RAM now rather than at the idle timeout.
        (application as OpenPhoneApp).appScope.launch { modelManager.release() }
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
        agentLoop = AgentLoop(modelManager) { step ->
            scope.launch { addLog("[${step.step}/${step.maxSteps}] ${step.status}") }
        }
        if (modelManager.installed() == null) {
            addLog("No model installed. Set one up in the app first.")
            return
        }
        scope.launch {
            combine(modelManager.state, modelManager.backend) { state, backend -> state to backend }
                .collect { (state, backend) -> showModelState(state, backend) }
        }
        // Load now, so the first step of the first goal is not the slow one.
        scope.launch {
            try {
                modelManager.warmUp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("Error: ${e.message}")
            }
        }
    }

    private fun showModelState(state: ModelState, backend: String?) {
        val (label, color) = when (state) {
            ModelState.LOADING -> "LOADING MODEL…" to R.color.overlay_muted
            ModelState.READY -> "READY · ${backend ?: "?"}" to R.color.accent
            ModelState.ERROR -> "MODEL ERROR" to android.R.color.holo_red_light
            ModelState.IDLE -> "IDLE" to R.color.overlay_idle_dot
            ModelState.NOT_INSTALLED -> "NO MODEL" to android.R.color.holo_red_light
        }
        if (!agentRunning) txtStatus.text = label
        statusDot.backgroundTintList = ContextCompat.getColorStateList(this, color)
    }

    private fun setRunning(running: Boolean) {
        agentRunning = running
        btnRun.isEnabled = !running
        btnRun.alpha = if (running) 0.35f else 1f
        btnStop.isEnabled = running
        btnStop.alpha = if (running) 1f else 0.35f
        txtStatus.text = if (running) "WORKING…" else txtStatus.text
        if (!running) showModelState(modelManager.state.value, modelManager.backend.value)
    }

    private fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed
        contentLayout.visibility = if (collapsed) View.GONE else View.VISIBLE
        btnCollapse.text = if (collapsed) "+" else "—"
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
        btnCollapse = overlayView.findViewById(R.id.btn_collapse)
        txtLog = overlayView.findViewById(R.id.txt_log)
        txtStatus = overlayView.findViewById(R.id.txt_status)
        statusDot = overlayView.findViewById(R.id.status_dot)
        contentLayout = overlayView.findViewById(R.id.overlay_content)

        val header = overlayView.findViewById<LinearLayout>(R.id.overlay_header)
        val btnClose = overlayView.findViewById<TextView>(R.id.btn_close)

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

        btnCollapse.setOnClickListener { setCollapsed(!isCollapsed) }

        btnClose.setOnClickListener {
            stopSelf()
        }

        btnRun.setOnClickListener { runGoal() }
        inputGoal.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runGoal()
                true
            } else false
        }

        btnStop.setOnClickListener {
            agentLoop.stop()
            addLog("Stopped")
        }

        setRunning(false)
        windowManager.addView(overlayView, params)
    }

    private fun runGoal() {
        val goal = inputGoal.text.toString().trim()
        if (goal.isBlank() || agentRunning || modelManager.installed() == null) return

        setRunning(true)
        txtLog.text = ""
        // Collapse so the agent sees (and taps) the app underneath, not the panel
        setCollapsed(true)

        scope.launch {
            addLog("Goal: $goal")
            try {
                agentLoop.run(goal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("Error: ${e.message}")
            }
            setRunning(false)
            // Expand to show results
            setCollapsed(false)
        }
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
