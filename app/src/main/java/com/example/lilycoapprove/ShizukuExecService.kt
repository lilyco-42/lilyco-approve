package com.example.lilycoapprove

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** 跑在 Shizuku 进程里的执行器：adb 等价的 shell 出口。 */
class ShizukuExecService : Service() {
  private val stub =
    object : IShizukuExec.Stub() {
      override fun exec(cmd: String): String {
        return try {
          val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
          val out = p.inputStream.bufferedReader().readText()
          val err = p.errorStream.bufferedReader().readText()
          val code = p.waitFor()
          "exit=$code\n$out$err".trim().ifEmpty { "执行成功（无输出）" }
        } catch (e: Exception) {
          "shell 异常：${e.message}"
        }
      }
    }

  override fun onBind(intent: Intent?): IBinder = stub
}
