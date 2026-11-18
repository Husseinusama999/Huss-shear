package com.huss.shear
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems-fp16.tar.bz2"

fun ensureModels(onP: (Float) -> Unit): Pair<File, File> {
  val d = File(appCtx.filesDir, "spleeter").also { it.mkdirs() }
  val v = File(d, "vocals.onnx"); val a = File(d, "accompaniment.onnx")
  if (v.length() > 1_000_000 && a.length() > 1_000_000) return v to a
  val tmp = File(appCtx.cacheDir, "spl.tar.bz2")
  val c = URL(MODEL_URL).openConnection() as HttpURLConnection
  c.connectTimeout = 30000; c.readTimeout = 60000
  val total = c.contentLengthLong
  c.inputStream.use { i ->
    tmp.outputStream().use { o ->
      val buf = ByteArray(262144); var n = 0L; var k = i.read(buf)
      while (k > 0) { o.write(buf, 0, k); n += k; if (total > 0) onP(n.toFloat() / total); k = i.read(buf) }
    }
  }
  TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(tmp)))).use { t ->
    var e = t.nextEntry
    while (e != null) {
      val dst = if (e.name.endsWith("vocals.fp16.onnx")) v else if (e.name.endsWith("accompaniment.fp16.onnx")) a else null
      if (dst != null) dst.outputStream().use { t.copyTo(it) }
      e = t.nextEntry
    }
  }
  tmp.delete()
  if (v.length() < 1_000_000 || a.length() < 1_000_000) throw Exception("model extract failed")
  return v to a
}

class Fft(val n: Int) {
  private val cs = FloatArray(n / 2) { Math.cos(2.0 * Math.PI * it / n).toFloat() }
  private val sn = FloatArray(n / 2) { (-Math.sin(2.0 * Math.PI * it / n)).toFloat() }
  private val rev = IntArray(n).also { r -> val bits = Integer.numberOfTrailingZeros(n); for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits) }
  fun fft(re: FloatArray, im: FloatArray) {
    for (i in 0 until n) { val j = rev[i]; if (j > i) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b } }
    var len = 2
    while (len <= n) {
      val half = len / 2; val step = n / len
      var i = 0
      while (i < n) {
        var k = 0
        for (j in i until i + half) {
          val xr = re[j + half] * cs[k] - im[j + half] * sn[k]
          val xi = re[j + half] * sn[k] + im[j + half] * cs[k]
          re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
          re[j] += xr; im[j] += xi
          k += step
        }
        i += len
      }
      len = len shl 1
    }
  }
}

class Sep(private val vModel: File, private val aModel: File) {
  private val N = 4096; private val H = 1024; private val FR = 512; private val BINS = N / 2 + 1
  private val SEG = 511 * 1024; private val OV = 16384; private val KEEP = SEG - 2 * OV
  private val fft = Fft(N)
  private val win = FloatArray(N) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / N)).toFloat() }
  private val env = OrtEnvironment.getEnvironment()

  private fun stft(x: FloatArray, re: FloatArray, im: FloatArray) {
    val br = FloatArray(N); val bi = FloatArray(N)
    for (f in 0 until FR) {
      for (i in 0 until N) { val idx = f * H + i - N / 2; br[i] = if (idx >= 0 && idx < x.size) x[idx] * win[i] else 0f; bi[i] = 0f }
      fft.fft(br, bi)
      System.arraycopy(br, 0, re, f * BINS, BINS); System.arraycopy(bi, 0, im, f * BINS, BINS)
    }
  }

  private fun istft(re: FloatArray, im: FloatArray, mask: FloatArray, moff: Int, vocals: Boolean): FloatArray {
    val br = FloatArray(N); val bi = FloatArray(N)
    val out = FloatArray(SEG + N); val ws = FloatArray(SEG + N)
    for (f in 0 until FR) {
      for (b in 0 until BINS) {
        val m = if (b < 1024) mask[moff + f * 1024 + b] else if (vocals) 0f else 1f
        br[b] = re[f * BINS + b] * m; bi[b] = im[f * BINS + b] * m
      }
      bi[0] = 0f; bi[N / 2] = 0f
      for (b in 1 until N / 2) { br[N - b] = br[b]; bi[N - b] = -bi[b] }
      for (i in 0 until N) bi[i] = -bi[i]
      fft.fft(br, bi)
      for (i in 0 until N) { out[f * H + i] += br[i] / N * win[i]; ws[f * H + i] += win[i] * win[i] }
    }
    return FloatArray(SEG) { out[it + N / 2] / maxOf(ws[it + N / 2], 1e-8f) }
  }

  private fun infer(s: OrtSession, x: FloatArray): FloatArray {
    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(2, 1, 512, 1024))
    try {
      s.run(mapOf("x" to t)).use { r ->
        val fb = (r.get(0) as OnnxTensor).floatBuffer
        val o = FloatArray(fb.remaining()); fb.get(o); return o
      }
    } finally { t.close() }
  }

  private fun process(l: FloatArray, r: FloatArray, sv: OrtSession, sa: OrtSession, vocals: Boolean): Pair<FloatArray, FloatArray> {
    val reL = FloatArray(FR * BINS); val imL = FloatArray(FR * BINS); val reR = FloatArray(FR * BINS); val imR = FloatArray(FR * BINS)
    stft(l, reL, imL); stft(r, reR, imR)
    val x = FloatArray(2 * FR * 1024)
    for (f in 0 until FR) for (b in 0 until 1024) {
      val i = f * BINS + b
      x[f * 1024 + b] = Math.sqrt((reL[i] * reL[i] + imL[i] * imL[i]).toDouble()).toFloat()
      x[FR * 1024 + f * 1024 + b] = Math.sqrt((reR[i] * reR[i] + imR[i] * imR[i]).toDouble()).toFloat()
    }
    val v = infer(sv, x); val a = infer(sa, x)
    val mask = FloatArray(x.size)
    for (i in mask.indices) {
      val s = v[i] * v[i] + a[i] * a[i] + 1e-10f
      mask[i] = if (vocals) (v[i] * v[i] + 5e-11f) / s else (a[i] * a[i] + 5e-11f) / s
    }
    return istft(reL, imL, mask, 0, vocals) to istft(reR, imR, mask, FR * 1024, vocals)
  }

  fun run(inFile: File, outFile: File, vocals: Boolean, onP: (Float) -> Unit) {
    val total = inFile.length() / 8
    val so = OrtSession.SessionOptions(); so.setIntraOpNumThreads(4)
    val sv = env.createSession(vModel.absolutePath, so); val sa = env.createSession(aModel.absolutePath, so)
    try {
      RandomAccessFile(inFile, "r").use { raf ->
        outFile.outputStream().use { fo ->
          val raw = ByteArray(SEG * 8); val ob = ByteBuffer.allocate(KEEP * 8).order(ByteOrder.LITTLE_ENDIAN)
          var keep = 0L
          while (keep < total) {
            val segStart = keep - OV
            val l = FloatArray(SEG); val r = FloatArray(SEG)
            val s0 = maxOf(0L, segStart); val s1 = minOf(total, segStart + SEG)
            if (s1 > s0) {
              val n = (s1 - s0).toInt(); raf.seek(s0 * 8); raf.readFully(raw, 0, n * 8)
              val bb = ByteBuffer.wrap(raw, 0, n * 8).order(ByteOrder.LITTLE_ENDIAN); val off = (s0 - segStart).toInt()
              for (i in 0 until n) { l[off + i] = bb.getFloat(); r[off + i] = bb.getFloat() }
            }
            val (oL, oR) = process(l, r, sv, sa, vocals)
            val cnt = minOf(KEEP.toLong(), total - keep).toInt()
            ob.clear(); for (i in 0 until cnt) { ob.putFloat(oL[OV + i]); ob.putFloat(oR[OV + i]) }
            fo.write(ob.array(), 0, cnt * 8)
            keep += KEEP; onP(minOf(1f, keep.toFloat() / total))
          }
        }
      }
    } finally { sv.close(); sa.close() }
  }
}
