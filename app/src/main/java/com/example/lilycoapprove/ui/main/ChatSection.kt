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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val role: String, val text: String)

/** 手机端对话（ChatGPT 范式：气泡 + 左右分区 + 自动滚底，端侧 JNI 优先、HTTP 备用）。 */
@Composable
internal fun ChatSection() {
  val ctx = LocalContext.current
  var history by remember { mutableStateOf(listOf(ChatMsg("assistant", "你好呀，我是小莉，住在这部手机里。点下面试试，不花流量。"))) }
  var input by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var engineState by remember { mutableStateOf("端侧引擎：加载中…") }
  var engine: InferenceEngine? by remember { mutableStateOf(null) }
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
        withContext(Dispatchers.Main) {
          engine = eng
          engineState = "端侧引擎：就绪（免 Termux）"
          history = listOf(ChatMsg("assistant", "我准备好啦，直接说事就行。"))
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
          busy = false
        }
      } else {
        val reply = postChat(history.dropLast(1).filter { it.text != "…" })
        withContext(Dispatchers.Main) {
          history = history.dropLast(1) + ChatMsg("assistant", reply)
          busy = false
        }
      }
      Unit
    }
  }

  fun send() = sendText(input)

  Column(Modifier.fillMaxWidth()) {
    Text("对话（本机离线）", style = MaterialTheme.typography.titleMedium)
    Text(engineState, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (history.size <= 1 && !busy) {
      SuggestionChips(onPick = { sendText(it) })
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
      Button(onClick = { send() }, enabled = !busy) { Text("发送") }
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
  text.replace(Regex("(?s)<think>.*?</think>"), "").trim()

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
