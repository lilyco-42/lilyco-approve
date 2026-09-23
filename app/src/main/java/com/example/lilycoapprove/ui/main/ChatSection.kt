package com.example.lilycoapprove.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.example.lilycoapprove.ModelSeed
import com.example.lilycoapprove.TtsManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val role: String, val text: String)

/** 通用动作（router 文本 → 结构化 → 无障碍直调；Shizuku-shell 通道见 v2 计划）。 */
private sealed interface OpAction {
  data class TapText(val text: String) : OpAction
  data class Tap(val x: Float, val y: Float) : OpAction
  data class Input(val text: String) : OpAction
  data class OpenApp(val pkg: String, val label: String) : OpAction
  data object Back : OpAction
  data object ScrollDown : OpAction
}

private val OP_TAP = Regex("""(?i)^\s*tap\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s*$""")
private val OP_TAP_TEXT = Regex("""(?i)^\s*(?:tap|click|点(?:击)?)\s*[“"']?(.+?)[""']?\s*$""")
private val OP_INPUT = Regex("""(?i)^\s*(?:input|type|输入)\s+(.+)\s*$""")
private val OP_OPEN = Regex("""(?i)^\s*(?:open|打开|启动)\s+([a-zA-Z][\w.]+)\s*$""")

/** 一行输出 → 动作；看不懂返回 null（纯聊天，不弹 Approve）。 */
private fun parseOp(line: String): OpAction? {
  val t = line.trim()
  if (t.isEmpty()) return null
  OP_TAP.matchEntire(t)?.let { return OpAction.Tap(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()) }
  OP_INPUT.matchEntire(t)?.let { return OpAction.Input(it.groupValues[1]) }
  OP_OPEN.matchEntire(t)?.let { return OpAction.OpenApp(it.groupValues[1], it.groupValues[1]) }
  if (t.equals("back", true) || t == "返回") return OpAction.Back
  if (t.equals("scroll", true) || t == "下滑" || t == "翻页") return OpAction.ScrollDown
  if (!t.contains(" ") && !t.contains("\n") && t.length <= 12) return OpAction.TapText(t)
  OP_TAP_TEXT.matchEntire(t)?.let { return OpAction.TapText(it.groupValues[1]) }
  return null
}

private fun describeOp(a: OpAction): String =
  when (a) {
    is OpAction.TapText -> "点「${a.text}」"
    is OpAction.Tap -> "点坐标 (${a.x}, ${a.y})"
    is OpAction.Input -> "输入「${a.text}」"
    is OpAction.OpenApp -> "打开 ${a.label}"
    OpAction.Back -> "返回"
    OpAction.ScrollDown -> "下滑翻页"
  }

/** 无障碍直调执行。 */
private fun runOp(a: OpAction, ctx: android.content.Context): String {
  val svc = com.example.lilycoapprove.ApproveAccessibilityService.instance
    ?: return "无障碍服务未开启，先去设置打开"
  return try {
    val ok =
      when (a) {
        is OpAction.TapText -> com.example.lilycoapprove.ApproveAccessibilityService.clickText(a.text)
        is OpAction.Tap -> com.example.lilycoapprove.ApproveAccessibilityService.tap(a.x, a.y)
        is OpAction.Input -> {
          val root = svc.rootInActiveWindow
          val node = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
          node?.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
              putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                a.text,
              )
            },
          ) ?: false
        }
        is OpAction.OpenApp -> {
          val intent = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
            ?: return "没装 ${a.label}，装上再批"
          intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(intent)
          true
        }
        OpAction.Back ->
          svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        OpAction.ScrollDown -> {
          val root = svc.rootInActiveWindow
          root?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
        }
      }
    if (ok) "已执行：${describeOp(a)}" else "执行失败（节点不在当前屏？）：${describeOp(a)}"
  } catch (e: Exception) {
    "执行异常：${e.message}"
  }
}

/** 当前屏可点文本（答题：喂给模型选，再点）。 */
private fun screenOpTexts(): List<String> {
  val root = com.example.lilycoapprove.ApproveAccessibilityService.instance?.rootInActiveWindow
    ?: return emptyList()
  val out = LinkedHashSet<String>()
  fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
    if (n == null) return
    val t = (n.text ?: n.contentDescription)?.toString()?.trim()
    if (!t.isNullOrEmpty() && t.length <= 40) out.add(t)
    for (i in 0 until n.childCount) walk(n.getChild(i))
  }
  walk(root)
  return out.toList()
}

/** router 脑的 system prompt（与训练一致：只吐命令）。 */
private const val ROUTER_SYS =
  // 人设只放 UI 气泡与 strip 兜底：实测 sys 里加身份行会把路由带偏（kubectl→gh）
  "你是 lyco_agent 的命令路由器。把用户的日常意图翻译成一个本地 CLI 命令。" +
    "支持的域：hw(硬件)/gh(github)/ff(ffmpeg)/lb(行情持仓只读)/brush(shell 通用命令)。" +
    "只输出命令本身，不要解释；不支持的请求输出 (无需调用硬件命令)。" +
    "能力表：kubectl get pods 看pod；brush docker ps 列运行中容器；" +
    "ff convert <in> <out> [宽] [高] 转码。" +
    "示例：用户<看看集群里有哪些pod在跑>输出<kubectl get pods>；" +
    "用户<列出正在运行的docker容器>输出<brush docker ps>。"

/** 看着像可执行命令的回复 → 弹出 Approve 条。 */
private fun extractCommand(text: String): String? {
  val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
  if (line.startsWith("(无需调用硬件命令)")) return null
  val head = line.split(Regex("\\s+")).firstOrNull() ?: return null
  return if (head in setOf("gh", "brush", "hw", "ff", "adb", "am", "input", "monkey", "cargo", "git", "ssh")) line
  else null
}

/** 手机端对话（ChatGPT 范式：气泡 + 左右分区 + 自动滚底，端侧 JNI 优先、HTTP 备用）。 */
@Composable
internal fun ChatSection() {
  val ctx = LocalContext.current
  var history by remember { mutableStateOf(listOf(ChatMsg("assistant", "你好呀，我是lyco璃可，住在这部手机里。点下面试试，不花流量。"))) }
  var input by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var engineState by remember { mutableStateOf("端侧引擎：加载中…") }
  var ttsState by remember { mutableStateOf("语音：随引擎一起加载…") }
  var voiceOn by remember { mutableStateOf(true) }
  var engine: InferenceEngine? by remember { mutableStateOf(null) }
  var pendingCmd by remember { mutableStateOf<String?>(null) }
  var execNote by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  LaunchedEffect(history.size) {
    if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
  }

  // 首启：铺模型 → 载入 JNI 引擎（后台全程）
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      try {
        withContext(Dispatchers.Main) { engineState = "端侧引擎：铺模型…" }
        val file =
          if (ModelSeed.isReady(ctx)) ModelSeed.modelFile(ctx)
          else {
            var last = -1
            ModelSeed.ensure(ctx) { p ->
              if (p != last) {
                last = p
                (ctx as? androidx.activity.ComponentActivity)?.runOnUiThread {
                  engineState = "端侧引擎：铺模型 $p%"
                }
              }
            }
          }
        withContext(Dispatchers.Main) { engineState = "端侧引擎：加载权重…" }
        val eng = AiChat.getInferenceEngine(ctx)
        eng.loadModel(file.absolutePath)
        try {
          eng.setSystemPrompt(ROUTER_SYS)
        } catch (_: Exception) {
        }
        withContext(Dispatchers.Main) {
          engine = eng
          engineState = "端侧引擎：就绪（免 Termux）"
          history = listOf(ChatMsg("assistant", "我准备好啦，直接说事就行。"))
          ttsState = "语音：铺模型…"
        }
        // 语音：跟引擎同一后台流初始化，失败不影响聊天
        val ttsOk = TtsManager.init(ctx.applicationContext)
        withContext(Dispatchers.Main) {
          ttsState = if (ttsOk) "语音：就绪（离线中文）" else "语音不可用"
          if (ttsOk && voiceOn) TtsManager.speak("我准备好啦，直接说事就行。")
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          engineState = "端侧引擎失败，转 HTTP 备用"
          history = listOf(ChatMsg("assistant", "端侧引擎未起（${e.message}），走 HTTP 备用。"))
        }
      }
      Unit
    }
  }

  fun sendText(raw: String) {
    val q = raw.trim()
    if (q.isEmpty() || busy) return
    input = ""
    pendingCmd = null
    execNote = ""
    history = history + ChatMsg("user", q) + ChatMsg("assistant", "…")
    busy = true
    val eng = engine
    scope.launch(Dispatchers.IO) {
      if (eng != null) {
        // 流式：token 到即上屏（Qwen3 关思考 + 兜底剥 think 块，小白只看答案）
        val sb = StringBuilder()
        var shown = ""
        try {
          eng.sendUserPrompt("$q/no_think", 128).collect { tok ->
            sb.append(tok)
            val cur = visibleText(sb.toString())
            if (cur.length - shown.length >= 2 || cur.endsWith("\n")) {
              shown = cur
              val snapshot = shown
              withContext(Dispatchers.Main) {
                history = history.dropLast(1) + ChatMsg("assistant", snapshot)
              }
            }
          }
        } catch (e: Exception) {
          sb.append("（生成中断）")
        }
        val final = stripThink(sb.toString()).trim().ifEmpty { "（空回复）" }
        withContext(Dispatchers.Main) {
          history = history.dropLast(1) + ChatMsg("assistant", final)
          pendingCmd = extractCommand(final)
          busy = false
          if (voiceOn && final != "（空回复）") TtsManager.speak(final)
        }
      } else {
        val reply = postChat(history.dropLast(1).filter { it.text != "…" })
        withContext(Dispatchers.Main) {
          history = history.dropLast(1) + ChatMsg("assistant", reply)
          busy = false
          if (voiceOn && reply != "（空回复）") TtsManager.speak(reply)
        }
      }
      Unit
    }
  }

  fun send() = sendText(input)

  Column(Modifier.fillMaxWidth()) {
    Text("对话（本机离线）", style = MaterialTheme.typography.titleMedium)
    Text(engineState, style = MaterialTheme.typography.bodySmall)
    Text(ttsState, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (history.size <= 1 && !busy) {
      TaskChips(onPick = { sendText(it) })
      Spacer(Modifier.height(8.dp))
    }
    LazyColumn(
      Modifier.heightIn(min = 200.dp, max = 420.dp).fillMaxWidth(),
      state = listState,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(history) { m -> ChatBubble(m) }
    }
    Spacer(Modifier.height(8.dp))
    pendingCmd?.let { cmd ->
      // 通用化：先解析成结构化动作（无障碍直调），解析不出才走生活类关键词兜底
      val actions = remember(cmd) { cmd.lines().mapNotNull { parseOp(it) }.take(5) }
      val userQ = history.lastOrNull { it.role == "user" }?.text.orEmpty()
      val negated = hasNegation(userQ)
      val cmdText =
        if (actions.isNotEmpty()) actions.joinToString("\n") { "· " + describeOp(it) }
        else cmd
      if (negated) {
        Text(
          "⚠ 检测到否定词，请逐字核对命令再批准",
          color = Color.Red,
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
      }
      ApproveCard(
        cmd = cmdText,
        note = execNote,
        onApprove = {
          val q = history.lastOrNull { it.role == "user" }?.text.orEmpty()
          val done =
            if (actions.isNotEmpty()) {
              actions.joinToString("\n") { a -> runOp(a, ctx) }
            } else {
              executeTask(ctx, q, cmd)
            }
          execNote = done
          history = history + ChatMsg("assistant", done)
          pendingCmd = null
        },
        onReject = {
          execNote = "已拒绝，不执行"
          history = history + ChatMsg("assistant", "已拒绝，不执行")
          pendingCmd = null
        },
      )
      Spacer(Modifier.height(8.dp))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("说点什么…") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors =
          TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
          ),
      )
      Spacer(Modifier.width(8.dp))
      Button(onClick = {
        voiceOn = !voiceOn
        if (!voiceOn) TtsManager.stop()
      }) { Text(if (voiceOn) "🔊" else "🔇") }
      Spacer(Modifier.width(8.dp))
      Button(onClick = { send() }, enabled = !busy) { Text("发送") }
    }
  }
}

@Composable
private fun TaskChips(onPick: (String) -> Unit) {
  val ctx = LocalContext.current
  val tasks =
    listOf(
      "打开网易云放一首歌" to "放首歌",
      "帮我点一份外卖" to "点外卖",
      "查一下特斯拉股价" to "查股价",
    )
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      tasks.forEach { (prompt, label) ->
        androidx.compose.material3.AssistChip(onClick = { onPick(prompt) }, label = { Text(label) })
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      androidx.compose.material3.AssistChip(
        onClick = {
          val texts = screenOpTexts()
          onPick(
            if (texts.isEmpty()) "帮我看看这屏（无障碍没开的话先去设置打开）"
            else "这屏可点项：" + texts.take(30).joinToString("、") + "。请帮我答题/选一项",
          )
        },
        label = { Text("帮我答题") },
      )
    }
  }
}

/** 三类有意思活的执行器：音乐/外卖走应用拉起，股价走 LongBridge（需 Key 则明示）。 */
private fun executeTask(ctx: android.content.Context, query: String, cmd: String): String {
  val pm = ctx.packageManager
  fun launch(pkg: String, label: String): String {
    val intent = pm.getLaunchIntentForPackage(pkg) ?: return "没装${label}，装上再点批准"
    ctx.startActivity(intent)
    return "已执行：打开${label}"
  }
  return when {
    query.contains("歌") || query.contains("网易云") || query.contains("播放") ->
      launch("com.netease.cloudmusic", "网易云音乐")
    query.contains("外卖") || query.contains("美团") -> launch("com.sankuai.meituan", "美团")
    query.contains("股价") || query.contains("股票") ->
      "已生成指令（$cmd）。LongBridge 实盘需先配 Key，演示版先带你开 App 看行情"
    else -> "已批准：$cmd（演示版只执行放歌/外卖/股价三类）"
  }
}

/** 否定词检测：命中则 Approve 卡标红，强制人工逐字核对（模型否定可靠性为零）。 */
private fun hasNegation(q: String): Boolean =
  listOf("不", "没", "别", "勿", "莫", "千万", "禁止", "拒绝", "不要").any { q.contains(it) }

@Composable
private fun ApproveCard(
  cmd: String,
  note: String,
  onApprove: () -> Unit,
  onReject: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.tertiaryContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(12.dp)) {
      Text("准备执行：", style = MaterialTheme.typography.labelMedium)
      Text(cmd, style = MaterialTheme.typography.bodyMedium)
      if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onApprove) { Text("批准执行") }
        androidx.compose.material3.OutlinedButton(onClick = onReject) { Text("拒绝") }
      }
    }
  }
}

@Composable
private fun SuggestionChips(onPick: (String) -> Unit) {
  val tips = listOf("你是谁？", "讲个笑话", "帮我写个请假条")
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    tips.forEach { tip ->
      androidx.compose.material3.AssistChip(onClick = { onPick(tip) }, label = { Text(tip) })
    }
  }
}

@Composable
private fun ChatBubble(m: ChatMsg) {
  val mine = m.role == "user"
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
    Surface(
      shape =
        RoundedCornerShape(
          topStart = 16.dp,
          topEnd = 16.dp,
          bottomStart = if (mine) 16.dp else 4.dp,
          bottomEnd = if (mine) 4.dp else 16.dp,
        ),
      color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.widthIn(max = 300.dp),
    ) {
      Text(
        m.text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

/** 流式可见文：think 未闭合时只露“思考中”，闭合后整段剥掉。 */
private fun visibleText(text: String): String {
  val open = text.indexOf("<think>")
  if (open >= 0 && !text.contains("</think>")) {
    val head = text.substring(0, open).trim()
    return (if (head.isNotEmpty()) head + "\n" else "") + "思考中…"
  }
  return stripThink(text)
}

private fun stripThink(text: String): String =
  text
    .replace(Regex("(?s)<think>.*?</think>"), "")
    .replace("我叫小智", "我叫lyco璃可")
    .trim()

private fun postChat(history: List<ChatMsg>): String {
  return try {
    val arr = JSONArray()
    for (m in history.takeLast(20)) {
      arr.put(JSONObject().put("role", m.role).put("content", m.text))
    }
    val body =
      JSONObject()
        .put("messages", arr)
        .put("temperature", 0.7)
        .put("max_tokens", 128)
        .put("stream", false)
        .toString()
    val conn = URL("http://127.0.0.1:8080/v1/chat/completions").openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 5000
      conn.readTimeout = 120000
      conn.setRequestProperty("Content-Type", "application/json")
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val text =
        (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
      if (code !in 200..299) return "服务报错 HTTP $code"
      JSONObject(text)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
        .trim()
        .ifEmpty { "（空回复）" }
    } finally {
      conn.disconnect()
    }
  } catch (e: Exception) {
    "连不上本机模型：${e.message}（先点一键启动模型）"
  }
}
