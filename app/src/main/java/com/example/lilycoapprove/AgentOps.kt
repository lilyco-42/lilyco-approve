package com.example.lilycoapprove

import android.accessibilityservice.AccessibilityService
import android.os.IBinder
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 通用 Agent 动作层：router/模型的文本输出 → 结构化 [Action] →
 * 无障碍直调（优先）或 Shizuku-shell（adb 等价）二选一执行。
 *
 * 视觉来源：端内用无障碍树（等价 adb dump 的 XML），复杂屏可把树喂给模型再回动作。
 */
sealed interface AgentAction {
  /** 按文本点（答题/点按钮最常用） */
  data class TapText(val text: String) : AgentAction
  /** 按坐标点（视觉 grounding 给归一化坐标时用） */
  data class Tap(val x: Float, val y: Float) : AgentAction
  /** 往当前焦点框输文本（答题填空） */
  data class Input(val text: String) : AgentAction
  /** 按文本找框并填入（选择题先点选项，填空题调这个） */
  data class FillText(val label: String, val text: String) : AgentAction
  /** 打开应用 */
  data class OpenApp(val pkg: String, val label: String) : AgentAction
  /** 返回键 */
  data object Back : AgentAction
  /** 滚屏（答题翻页） */
  data object ScrollDown : AgentAction
}

object AgentOps {
  private val TAP_RE = Regex("""(?i)^\s*tap\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s*$""")
  private val TAP_TEXT_RE = Regex("""(?i)^\s*(?:tap|click|点(?:击)?)\s*[“"']?(.+?)[”"']?\s*$""")
  private val INPUT_RE = Regex("""(?i)^\s*(?:input|type|输入)\s+(.+)\s*$""")
  private val OPEN_RE = Regex("""(?i)^\s*(?:open|打开|启动)\s+([a-zA-Z][\w.]+)\s*$""")

  /** router/模型的一行输出 → 动作；看不懂返回 null（纯聊天，不弹 Approve）。 */
  fun parse(line: String): AgentAction? {
    val t = line.trim()
    if (t.isEmpty()) return null
    TAP_RE.matchEntire(t)?.let { return AgentAction.Tap(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()) }
    INPUT_RE.matchEntire(t)?.let { return AgentAction.Input(it.groupValues[1]) }
    OPEN_RE.matchEntire(t)?.let { return AgentAction.OpenApp(it.groupValues[1], it.groupValues[1]) }
    if (t.equals("back", true) || t == "返回") return AgentAction.Back
    if (t.equals("scroll", true) || t == "下滑" || t == "翻页") return AgentAction.ScrollDown
    // 中文短句（≤8 字）默认按文本点——答题点选项的主路径
    if (!t.contains(" ") && !t.contains("\n") && t.length <= 12) return AgentAction.TapText(t)
    TAP_TEXT_RE.matchEntire(t)?.let { return AgentAction.TapText(it.groupValues[1]) }
    return null
  }

  fun describe(a: AgentAction): String =
    when (a) {
      is AgentAction.TapText -> "点「${a.text}」"
      is AgentAction.Tap -> "点坐标 (${a.x}, ${a.y})"
      is AgentAction.Input -> "输入「${a.text}」"
      is AgentAction.FillText -> "在「${a.label}」填「${a.text}」"
      is AgentAction.OpenApp -> "打开 ${a.label}"
      AgentAction.Back -> "返回"
      AgentAction.ScrollDown -> "下滑翻页"
    }

  /** 无障碍直调（优先：快、无需额外授权 beyond 服务本身）。 */
  fun runA11y(a: AgentAction, ctx: android.content.Context): String {
    val svc = ApproveAccessibilityService.instance ?: return "无障碍服务未开启，先去设置打开"
    return try {
      val ok =
        when (a) {
          is AgentAction.TapText -> ApproveAccessibilityService.clickText(a.text)
          is AgentAction.Tap -> ApproveAccessibilityService.tap(a.x, a.y)
          is AgentAction.Input -> {
            val root = svc.rootInActiveWindow
            val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            node?.performAction(
              AccessibilityNodeInfo.ACTION_SET_TEXT,
              android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.text)
              },
            ) ?: false
          }
          is AgentAction.FillText -> {
            val node = ApproveAccessibilityService.findByText(a.label) ?: return "没找到「${a.label}」"
            node.performAction(
              AccessibilityNodeInfo.ACTION_SET_TEXT,
              android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.text)
              },
            )
          }
          is AgentAction.OpenApp -> {
            val intent = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
              ?: return "没装 ${a.label}，装上再批"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
          }
          AgentAction.Back -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
          AgentAction.ScrollDown -> {
            val root = svc.rootInActiveWindow
            root?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
          }
        }
      if (ok) "已执行：${describe(a)}" else "执行失败（节点不在当前屏？）：${describe(a)}"
    } catch (e: Exception) {
      "执行异常：${e.message}"
    }
  }

  /** Shizuku-UserService（adb 等价：dump/xml、input、am，备用通道）。 */
  fun runShell(ctx: android.content.Context, cmd: String): String {
    if (!rikka.shizuku.Shizuku.pingBinder()) return "Shizuku 未运行"
    if (rikka.shizuku.Shizuku.checkSelfPermission() !=
      android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      return "Shizuku 未授权"
    }
    val args =
      rikka.shizuku.Shizuku.UserServiceArgs(
        android.content.ComponentName(ctx, ShizukuExecService::class.java),
      ).daemon(false)
    var binder: IBinder? = null
    val latch = java.util.concurrent.CountDownLatch(1)
    val conn =
      object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
          binder = service
          latch.countDown()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
          latch.countDown()
        }
      }
    return try {
      rikka.shizuku.Shizuku.bindUserService(args, conn)
      if (!latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) return "Shizuku 服务连接超时"
      val b = binder ?: return "Shizuku 服务未就绪"
      IShizukuExec.Stub.asInterface(b).exec(cmd)
    } catch (e: Exception) {
      "shell 异常：${e.message}"
    } finally {
      try {
        rikka.shizuku.Shizuku.unbindUserService(args, conn, true)
      } catch (_: Exception) {
      }
    }
  }

  /** 一句话答题：把当前屏可点文本喂给模型选，再点。调用方拼好 prompt 调模型即可。 */
  fun screenTexts(): List<String> {
    val root = ApproveAccessibilityService.instance?.rootInActiveWindow ?: return emptyList()
    val out = LinkedHashSet<String>()
    fun walk(n: AccessibilityNodeInfo?) {
      if (n == null) return
      val t = (n.text ?: n.contentDescription)?.toString()?.trim()
      if (!t.isNullOrEmpty() && t.length <= 40) out.add(t)
      for (i in 0 until n.childCount) walk(n.getChild(i))
    }
    walk(root)
    return out.toList()
  }
}
