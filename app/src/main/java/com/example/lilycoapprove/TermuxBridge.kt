package com.example.lilycoapprove

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 到 Termux 的一键桥：推理跑在 Termux llama.cpp 里，APK 只管发令与收结果。 */
object TermuxBridge {
  const val TERMUX_PKG = "com.termux"
  const val ACTION_RUN = "com.termux.RUN_COMMAND"
  const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
  const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
  const val EXTRA_BG = "com.termux.RUN_COMMAND_BACKGROUND"
  const val ONKEY_SCRIPT = ".lilyco/termux-onekey.sh"

  fun isTermuxInstalled(ctx: Context): Boolean =
    try {
      ctx.packageManager.getPackageInfo(TERMUX_PKG, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  /**
   * 发令：在 Termux 后台跑脚本。true=intent 发出（Termux 是否真执行看 done 文件/服务状态），
   * false=系统直接拒收（通常是 Termux 未装）。
   */
  fun runOnekey(ctx: Context, vision: Boolean): Boolean {
    // 脚本位：用户按 docs/ONEKEY-XIAOBAI.md 已放入 Termux 家目录
    val script = "/data/data/$TERMUX_PKG/files/home/$ONKEY_SCRIPT"
    val args = if (vision) arrayOf(script, "--vision") else arrayOf(script)
    val intent =
      Intent(ACTION_RUN).apply {
        setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
        putExtra(EXTRA_PATH, "/data/data/$TERMUX_PKG/files/usr/bin/bash")
        putExtra(EXTRA_ARGS, args)
        putExtra(EXTRA_BG, true)
      }
    return try {
      ctx.startForegroundService(intent) ?: return false
      true
    } catch (_: Exception) {
      false
    }
  }

  fun serverHealth(): Boolean =
    try {
      java.net.URL("http://127.0.0.1:8080/health").openConnection().apply {
        connectTimeout = 1500
        readTimeout = 1500
      }.getInputStream().close()
      true
    } catch (_: Exception) {
      false
    }
}
