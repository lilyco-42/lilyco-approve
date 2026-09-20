package com.example.lilycoapprove

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 无障碍执行器：读全树 + 代点，是“自动 XML 勾选和操作”的端侧实现。 */
class ApproveAccessibilityService : AccessibilityService() {

  companion object {
    @Volatile var instance: ApproveAccessibilityService? = null
      private set

    fun findByText(text: String): AccessibilityNodeInfo? {
      val root = instance?.rootInActiveWindow ?: return null
      return root.findAccessibilityNodeInfosByText(text).firstOrNull()
    }

    fun clickText(text: String): Boolean {
      val node = findByText(text) ?: return false
      if (node.isClickable) {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
      var p = node.parent
      while (p != null) {
        if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        p = p.parent
      }
      return false
    }

    fun tap(x: Float, y: Float): Boolean {
      val svc = instance ?: return false
      val path = Path().apply { moveTo(x, y) }
      val stroke = GestureDescription.StrokeDescription(path, 0, 80)
      val gesture = GestureDescription.Builder().addStroke(stroke).build()
      return svc.dispatchGesture(gesture, null, null)
    }
  }

  override fun onServiceConnected() {
    instance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

  override fun onInterrupt() {}

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    instance = null
    return super.onUnbind(intent)
  }
}
