package com.example.lilycoapprove

import android.content.Context
import java.io.File

/** 首启铺模型：assets/models → filesDir/models（mmap 直读，一次即可）。 */
object ModelSeed {
  const val ASSET_PATH = "models/Qwen3-0.6B-Q4_K_M.gguf"
  const val ASSET_SIZE = 396705472L

  fun modelFile(ctx: Context): File = File(ctx.filesDir, "models/Qwen3-0.6B-Q4_K_M.gguf")

  fun isReady(ctx: Context): Boolean {
    val f = modelFile(ctx)
    return f.isFile && f.length() == ASSET_SIZE
  }

  /** 复制（IO 线程调），onProgress 传 0..100。 */
  fun ensure(ctx: Context, onProgress: (Int) -> Unit): File {
    val out = modelFile(ctx)
    if (isReady(ctx)) {
      onProgress(100)
      return out
    }
    out.parentFile?.mkdirs()
    ctx.assets.open(ASSET_PATH).use { ins ->
      out.outputStream().use { os ->
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var last = -1
        while (true) {
          val n = ins.read(buf)
          if (n < 0) break
          os.write(buf, 0, n)
          done += n
          val p = (done * 100 / ASSET_SIZE).toInt()
          if (p != last) {
            last = p
            onProgress(p)
          }
        }
      }
    }
    onProgress(100)
    return out
  }
}
