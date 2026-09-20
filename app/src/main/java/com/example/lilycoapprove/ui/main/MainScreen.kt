package com.example.lilycoapprove.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.lilycoapprove.TermuxBridge
import com.example.lilycoapprove.data.DefaultDataRepository
import com.example.lilycoapprove.theme.LilycoApproveTheme
import rikka.shizuku.Shizuku

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  when (state) {
    MainScreenUiState.Loading -> {
      // Blank
    }
    is MainScreenUiState.Success -> {
      MainScreen(data = (state as MainScreenUiState.Success).data, modifier = modifier)
    }
    is MainScreenUiState.Error -> {
      Text("Error loading data: ${(state as MainScreenUiState.Error).throwable.message}")
    }
  }
}

@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
  var showSettings by remember { mutableStateOf(false) }
  Column(modifier) {
    Text("小莉 · 本机 AI", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
    Text("断网能用，聊天不出手机", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    ChatSection()
    Spacer(Modifier.height(12.dp))
    Button(onClick = { showSettings = !showSettings }) {
      Text(if (showSettings) "收起设置" else "设置（授权 / 模型）")
    }
    if (showSettings) {
      Spacer(Modifier.height(8.dp))
      PermissionSection()
      Spacer(Modifier.height(16.dp))
      ModelSection()
    }
  }
}

@Composable
internal fun PermissionSection() {
  val ctx = LocalContext.current
  var shizukuState by remember { mutableStateOf("未检查") }
  var a11yState by remember { mutableStateOf("未检查") }

  fun refresh() {
    shizukuState =
      if (!Shizuku.pingBinder()) "Shizuku 未运行（需 adb/无线调试启动一次）"
      else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权"
      else "Shizuku 运行中，未授权"
    val enabled =
      Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty()
    a11yState =
      if (enabled.contains("${ctx.packageName}/.ApproveAccessibilityService")) "无障碍已开启"
      else "无障碍未开启"
  }

  Text("Shizuku：$shizukuState")
  Button(
    onClick = {
      if (Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
      ) {
        @Suppress("DEPRECATION") Shizuku.requestPermission(1001)
      }
      refresh()
    }
  ) {
    Text("请求 Shizuku 授权")
  }
  Spacer(Modifier.height(8.dp))
  Text("无障碍：$a11yState")
  Button(
    onClick = {
      ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
      refresh()
    }
  ) {
    Text("去开启无障碍服务")
  }
  Spacer(Modifier.height(8.dp))
  Button(onClick = { refresh() }) { Text("刷新状态") }
}

@Composable
internal fun ModelSection() {
  val ctx = LocalContext.current
  var modelState by remember { mutableStateOf("未检查") }

  fun refresh() {
    val hasTermux = TermuxBridge.isTermuxInstalled(ctx)
    // 网络探测走后台线程（主线程联网会被系统掐掉）
    Thread {
      val online = TermuxBridge.serverHealth()
      (ctx as? androidx.activity.ComponentActivity)?.runOnUiThread {
        modelState =
          when {
            !hasTermux -> "未装 Termux（去 F-Droid 装）"
            online -> "模型服务在线（127.0.0.1:8080）"
            else -> "Termux 已装，模型服务未起"
          }
      }
    }.start()
  }

  Text("模型：$modelState")
  Button(
    onClick = {
      if (!TermuxBridge.runOnekey(ctx, vision = false)) {
        modelState = "Termux 未装，发令失败"
      } else {
        modelState = "已发令，后台下载+启动中…"
      }
    }
  ) {
    Text("一键启动模型")
  }
  Spacer(Modifier.height(8.dp))
  Button(onClick = { refresh() }) { Text("检查模型状态") }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  LilycoApproveTheme { MainScreen(listOf("Android")) }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
fun MainScreenPortraitPreview() {
  LilycoApproveTheme { MainScreen(listOf("Android")) }
}
