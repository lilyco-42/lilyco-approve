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
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val role: String, val text: String)

/** 手机端对话（ChatGPT 范式：气泡 + 左右分区 + 自动滚底，直连本机 127.0.0.1:8080）。 */
@Composable
internal fun ChatSection() {
  var history by remember { mutableStateOf(listOf(ChatMsg("assistant", "本机模型已就绪，你说。"))) }
  var input by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  LaunchedEffect(history.size) {
    if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
  }

  fun send() {
    val q = input.trim()
    if (q.isEmpty() || busy) return
    input = ""
    history = history + ChatMsg("user", q) + ChatMsg("assistant", "…")
    busy = true
    scope.launch(Dispatchers.IO) {
      val reply = postChat(history.dropLast(1).filter { it.text != "…" })
      withContext(Dispatchers.Main) {
        history = history.dropLast(1) + ChatMsg("assistant", reply)
        busy = false
      }
      Unit
    }
  }

  Column(Modifier.fillMaxWidth()) {
    Text("对话（本机离线）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
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
