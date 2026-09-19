package com.phoneworker.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class BridgeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: BridgeAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        currentPackage()?.let {
            ForegroundEventRecorder.recordTransition(applicationContext, it)
            FocusSessionManager.onPackageChanged(applicationContext, it)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.trim().orEmpty()
        if (pkg.isNotBlank()) {
            val atMs = event?.eventTime ?: System.currentTimeMillis()
            ForegroundEventRecorder.recordTransition(applicationContext, pkg, atMs)
            FocusSessionManager.onPackageChanged(applicationContext, pkg, atMs)
        }
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun uiTree(maxNodes: Int = 300): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("nodes", JSONArray())
        val out = JSONArray()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes) return
            count++
            val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
            out.put(JSONObject()
                .put("text", node.text?.toString())
                .put("content_desc", node.contentDescription?.toString())
                .put("view_id", node.viewIdResourceName)
                .put("class", node.className?.toString())
                .put("clickable", node.isClickable)
                .put("focusable", node.isFocusable)
                .put("focused", node.isFocused)
                .put("editable", node.isEditable)
                .put("depth", depth)
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return JSONObject().put("package", currentPackage()).put("nodes", out).put("truncated", count >= maxNodes)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 3000))).build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
