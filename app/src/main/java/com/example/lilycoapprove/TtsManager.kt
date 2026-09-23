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
      val espeakDir = File(base, "espeak-ng-data").let { if (it.isDirectory) it.absolutePath else "" }
      // 中文 TN/多音字规则（官方 run-vits-zh-aishell3.sh 同款；缺失则传空=不用）
      val ruleFsts =
        listOf("phone.fst", "date.fst", "number.fst")
          .map { File(base, it) }
          .filter { it.isFile }
          .joinToString(",") { it.absolutePath }
      val ruleFars = File(base, "rule.far").let { if (it.isFile) it.absolutePath else "" }
      // 官方 AAR 是 Kotlin data class：无 builder，用无参构造 + 属性赋值
      val vitsCfg =
        OfflineTtsVitsModelConfig().apply {
          model = onnx.absolutePath
          tokens = f("tokens.txt")
          lexicon = f("lexicon.txt")
          dataDir = espeakDir
          noiseScale = 0.667f
          noiseScaleW = 0.8f
          lengthScale = 1.0f
        }
      val modelCfg =
        OfflineTtsModelConfig().apply {
          vits = vitsCfg
          numThreads = 2
          debug = false
        }
      val ttsCfg =
        OfflineTtsConfig().apply {
          model = modelCfg
          ruleFsts = ruleFsts
          ruleFars = ruleFars
        }
      // assetManager=null → 走 newFromFile（模型已铺到 filesDir，用绝对路径）
      tts = OfflineTts(assetManager = null, config = ttsCfg)
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
        // sid=33：官方 demo 用过的多说话人 id（10/33/99 均可）
        val audio = t.generate(s, 33, 1.0f)
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
