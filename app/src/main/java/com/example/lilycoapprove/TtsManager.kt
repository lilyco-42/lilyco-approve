package com.example.lilycoapprove

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 端侧中文 TTS（sherpa-onnx vits，纯离线）。
 * 铁律：只用阻塞式 generate()（后台单线程）——generateWithCallback 有 JNI 线程真 bug，会 SIGABRT。
 */
object TtsManager {
  private const val TAG = "LycoTts"
  private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "lyco-tts").apply { isDaemon = true } }
  @Volatile private var tts: OfflineTts? = null
  @Volatile private var track: AudioTrack? = null
  private var genTask: Future<*>? = null

  val ready: Boolean get() = tts != null

  /** IO 线程调。返回 true=可用；失败不抛，只记日志（聊天不受影响）。 */
  fun init(ctx: Context): Boolean {
    if (tts != null) return true
    return try {
      val dir = TtsSeed.ensure(ctx) {}
      val onnx = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "onnx" }
        ?: return false.also { Log.w(TAG, "no onnx under $dir") }
      val base = onnx.parentFile ?: return false
      fun f(name: String) = File(base, name).let { if (it.isFile) it.absolutePath else "" }
      val dataDir = File(base, "espeak-ng-data").let { if (it.isDirectory) it.absolutePath else "" }
      val vits =
        OfflineTtsVitsModelConfig.builder()
          .setModel(onnx.absolutePath)
          .setTokens(f("tokens.txt"))
          .setLexicon(f("lexicon.txt"))
          .setDataDir(dataDir)
          .setNoiseScale(0.667f)
          .setNoiseScaleW(0.8f)
          .setLengthScale(1.0f)
          .build()
      val model = OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(2).setDebug(false).build()
      val config = OfflineTtsConfig.builder().setModel(model).build()
      tts = OfflineTts(config)
      Log.i(TAG, "init ok: ${onnx.name}")
      true
    } catch (e: Exception) {
      Log.w(TAG, "init failed: ${e.message}")
      false
    }
  }

  /** 异步合成+播放（主线程可调）；超长截断，打断上一次。 */
  fun speak(text: String) {
    val t = tts ?: return
    val s = text.replace(Regex("(?s)<think>.*?</think>"), "").trim().take(300)
    if (s.isEmpty()) return
    genTask?.cancel(true)
    stopTrack()
    genTask = exec.submit {
      try {
        val audio = t.generate(s, 0, 1.0f)
        val pcm = audio.samples
        if (pcm.isEmpty()) return@submit
        Log.i(TAG, "generated ${pcm.size} samples @ ${audio.sampleRate}Hz for ${s.length} chars")
        play16(pcm, audio.sampleRate)
      } catch (e: Exception) {
        Log.w(TAG, "speak failed: ${e.message}")
      }
    }
  }

  fun stop() {
    genTask?.cancel(true)
    genTask = null
    stopTrack()
  }

  private fun play16(pcm: FloatArray, rate: Int) {
    val shorts = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    val tr =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setBufferSizeInBytes(shorts.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track = tr
    tr.write(shorts, 0, shorts.size)
    tr.play()
  }

  private fun stopTrack() {
    try {
      track?.stop()
    } catch (_: Exception) {
    }
    try {
      track?.release()
    } catch (_: Exception) {
    }
    track = null
  }
}
