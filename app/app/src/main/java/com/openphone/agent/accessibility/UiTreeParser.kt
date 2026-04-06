package com.openphone.agent.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Parses AccessibilityNodeInfo tree into the same text format as agent.py's parse_ui_tree().
 * Format: [idx] ShortClassName "label" (resource_id) [properties] @ (cx,cy)
 */
object UiTreeParser {

    fun parse(root: AccessibilityNodeInfo): String {
        val elements = mutableListOf<String>()
        val counter = intArrayOf(0)
        traverse(root, elements, counter)
        return if (elements.isEmpty()) "[No UI elements found]"
        else elements.joinToString("\n")
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        elements: MutableList<String>,
        counter: IntArray
    ) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val clickable = node.isClickable
        val scrollable = node.isScrollable

        // Skip invisible/empty elements (same filter as agent.py)
        if (text.isEmpty() && desc.isEmpty() && !clickable && !scrollable) {
            // Still traverse children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it, elements, counter) }
            }
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip zero-area elements
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it, elements, counter) }
            }
            return
        }

        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val label = text.ifEmpty { desc }
        val className = node.className?.toString() ?: ""
        val shortCls = if (className.contains(".")) className.substringAfterLast(".") else className

        val props = mutableListOf<String>()
        if (clickable) props.add("clickable")
        if (scrollable) props.add("scrollable")
        if (node.isCheckable) props.add("checked=${node.isChecked}")
        if (node.isFocused) props.add("focused")

        val rid = node.viewIdResourceName
            ?.substringAfterLast("/", "")
            ?: ""

        val idx = counter[0]
        counter[0]++

        val sb = StringBuilder()
        sb.append("[$idx] $shortCls")
        if (label.isNotEmpty()) sb.append(" \"$label\"")
        if (rid.isNotEmpty()) sb.append(" ($rid)")
        if (props.isNotEmpty()) sb.append(" [${props.joinToString(", ")}]")
        sb.append(" @ ($cx,$cy)")

        elements.add(sb.toString())

        // Traverse children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverse(it, elements, counter) }
        }
    }
}
