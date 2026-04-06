package com.openphone.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PhoneControlService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneControl"
        var instance: PhoneControlService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility Service destroyed")
        super.onDestroy()
    }

    fun getUiTree(): String {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "getUiTree: rootInActiveWindow is null")
            return "[No window available]"
        }
        val tree = UiTreeParser.parse(root)
        Log.i(TAG, "getUiTree: ${tree.lines().size} elements")
        return tree
    }

    fun getScreenSize(): Pair<Int, Int> {
        val dm: DisplayMetrics = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    fun performTap(x: Int, y: Int): Boolean {
        Log.i(TAG, "performTap($x, $y)")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val result = dispatchGesture(gesture, null, null)
        Log.i(TAG, "performTap result: $result")
        return result
    }

    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        Log.i(TAG, "performSwipe($x1,$y1 -> $x2,$y2)")
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val result = dispatchGesture(gesture, null, null)
        Log.i(TAG, "performSwipe result: $result")
        return result
    }

    fun typeText(text: String): Boolean {
        Log.i(TAG, "typeText: $text")
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedInput(root)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "typeText result: $result")
            return result
        }
        Log.w(TAG, "typeText: no focused input field found")
        return false
    }

    fun pressHome(): Boolean {
        Log.i(TAG, "pressHome")
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressBack(): Boolean {
        Log.i(TAG, "pressBack")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressRecents(): Boolean {
        Log.i(TAG, "pressRecents")
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun openApp(packageName: String): Boolean {
        Log.i(TAG, "openApp: $packageName")
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "openApp: no launch intent for $packageName")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedInput(child)
            if (found != null) return found
        }
        return null
    }
}
