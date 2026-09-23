package com.example.lilycoapprove

import android.content.Context
import java.io.File

/** 首启铺 TTS：assets/tts → filesDir/tts（直读，一次即可）。marker + onnx 双重判定。 */
object TtsSeed {
  const val ASSET_DIR = "tts"
  private const val READY_MARK = ".tts_ready"

  fun ttsDir(ctx: Context): File = File(ctx.filesDir, "tts")

  fun isReady(ctx: Context): Boolean {
    val d = ttsDir(ctx)
    return File(d, READY_MARK).isFile && d.walkTopDown().any { it.isFile && it.extension == "onnx" }
  }

  /** 递归拷贝（IO 线程调），onProgress 传 0..100（估算值）。 */
  fun ensure(ctx: Context, onProgress: (Int) -> Unit): File {
    val out = ttsDir(ctx)
    if (isReady(ctx)) {
      onProgress(100)
      return out
    }
    out.deleteRecursively()
    out.mkdirs()
    var done = 0L
    val total = 100L * 1024 * 1024 // 进度估算分母，钳制 0..99
    fun copy(prefix: String, dst: File) {
      for (n in ctx.assets.list(prefix).orEmpty()) {
        val ap = if (prefix.isEmpty()) n else "$prefix/$n"
        if (ctx.assets.list(ap).orEmpty().isNotEmpty()) {
          val sub = File(dst, n)
          sub.mkdirs()
          copy(ap, sub)
        } else {
          ctx.assets.open(ap).use { ins ->
            File(dst, n).outputStream().use { os ->
              val buf = ByteArray(1 shl 20)
              while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                os.write(buf, 0, r)
                done += r
              }
            }
          }
          onProgress(((done * 100 / total).toInt()).coerceIn(0, 99))
        }
      }
    }
    copy(ASSET_DIR, out)
    File(out, READY_MARK).writeText("ok")
    onProgress(100)
    return out
  }
}
