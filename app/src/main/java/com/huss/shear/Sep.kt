package com.huss.shear
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

const val MDX_BASE = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/"

fun ensureMdx(vocals: Boolean, onP: (Float) -> Unit): File {
  val name = if (vocals) "UVR-MDX-NET-Voc_FT.onnx" else "UVR-MDX-NET-Inst_HQ_3.onnx"
  val d = File(appCtx.filesDir, "mdx").also { it.mkdirs() }
  val f = File(d, name)
  if (f.length() > 60_000_000) return f
  val tmp = File(d, "$name.part")
  val c = URL(MDX_BASE + name).openConnection() as HttpURLConnection
  c.connectTimeout = 30000; c.readTimeout = 60000
  val total = c.contentLengthLong
  c.inputStream.use { i ->
    tmp.outputStream().use { o ->
      val buf = ByteArray(262144); var n = 0L; var k = i.read(buf)
      while (k > 0) { o.write(buf, 0, k); n += k; if (total > 0) onP(n.toFloat() / total); k = i.read(buf) }
    }
  }
  if (tmp.length() < 60_000_000) { tmp.delete(); throw Exception("model download incomplete") }
  tmp.renameTo(f)
  return f
}

/** FFT of any size n = (power of two) * (small odd factor), e.g. 7680 = 512*15 and 6144 = 2048*3. */
class Fft(val n: Int) {
  private val n1: Int
  private val n2: Int
  init { var a = n; var p = 1; while (a % 2 == 0) { a /= 2; p *= 2 }; n1 = p; n2 = a }
  private val cs = FloatArray(n1 / 2) { Math.cos(2.0 * Math.PI * it / n1).toFloat() }
  private val sn = FloatArray(n1 / 2) { (-Math.sin(2.0 * Math.PI * it / n1)).toFloat() }
  private val rev = IntArray(n1).also { r -> val bits = Integer.numberOfTrailingZeros(n1); for (i in 0 until n1) r[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits) }
  private val twr = FloatArray(n) { Math.cos(2.0 * Math.PI * ((it / n1).toLong() * (it % n1)) / n).toFloat() }
  private val twi = FloatArray(n) { (-Math.sin(2.0 * Math.PI * ((it / n1).toLong() * (it % n1)) / n)).toFloat() }
  private val dr = FloatArray(n2 * n2) { Math.cos(2.0 * Math.PI * ((it / n2) * (it % n2)) / n2).toFloat() }
  private val di = FloatArray(n2 * n2) { (-Math.sin(2.0 * Math.PI * ((it / n2) * (it % n2)) / n2)).toFloat() }
  private val tr = FloatArray(n1); private val ti = FloatArray(n1)
  private val yr = FloatArray(n); private val yi = FloatArray(n)

  private fun pow2(re: FloatArray, im: FloatArray) {
    for (i in 0 until n1) { val j = rev[i]; if (j > i) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b } }
    var len = 2
    while (len <= n1) {
      val half = len / 2; val step = n1 / len
      var i = 0
      while (i < n1) {
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

  fun fft(re: FloatArray, im: FloatArray) {
    for (a in 0 until n2) {
      for (j in 0 until n1) { tr[j] = re[a + n2 * j]; ti[j] = im[a + n2 * j] }
      pow2(tr, ti)
      for (k in 0 until n1) {
        val idx = a * n1 + k
        yr[idx] = tr[k] * twr[idx] - ti[k] * twi[idx]
        yi[idx] = tr[k] * twi[idx] + ti[k] * twr[idx]
      }
    }
    for (k in 0 until n1) for (k2 in 0 until n2) {
      var sr = 0f; var si = 0f
      for (a in 0 until n2) {
        val idx = a * n1 + k; val c = dr[a * n2 + k2]; val s = di[a * n2 + k2]
        sr += yr[idx] * c - yi[idx] * s; si += yr[idx] * s + yi[idx] * c
      }
      re[k + n1 * k2] = sr; im[k + n1 * k2] = si
    }
  }
}

/** MDX-Net separation (UVR models): n_fft 7680 for Voc_FT, 6144 for Inst_HQ_3. Input/output: raw f32le stereo 44.1 kHz. */
class Mdx(private val model: File, private val nfft: Int, private val overlap: Float = 0.25f) {
  private val H = 1024; private val DIMF = 3072; private val DIMT = 256
  private val CH = H * (DIMT - 1)
  private val trim = nfft / 2
  private val fft = Fft(nfft)
  private val win = FloatArray(nfft) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / nfft)).toFloat() }
  private val hann = FloatArray(CH) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (CH - 1))).toFloat() }
  private val env = OrtEnvironment.getEnvironment()
  private val pl = FloatArray(CH + nfft); private val pr = FloatArray(CH + nfft)
  private val br = FloatArray(nfft); private val bi = FloatArray(nfft)

  private fun spec(l: FloatArray, r: FloatArray, x: FloatArray) {
    for (i in 0 until CH + nfft) {
      var idx = i - trim
      if (idx < 0) idx = -idx
      if (idx >= CH) idx = 2 * (CH - 1) - idx
      pl[i] = l[idx]; pr[i] = r[idx]
    }
    for (f in 0 until DIMT) {
      for (i in 0 until nfft) { br[i] = pl[f * H + i] * win[i]; bi[i] = pr[f * H + i] * win[i] }
      fft.fft(br, bi)
      for (k in 0 until DIMF) {
        if (k < 3) {
          x[(0 * DIMF + k) * DIMT + f] = 0f; x[(1 * DIMF + k) * DIMT + f] = 0f; x[(2 * DIMF + k) * DIMT + f] = 0f; x[(3 * DIMF + k) * DIMT + f] = 0f
        } else {
          val m = (nfft - k) % nfft
          x[(0 * DIMF + k) * DIMT + f] = (br[k] + br[m]) * 0.5f
          x[(1 * DIMF + k) * DIMT + f] = (bi[k] - bi[m]) * 0.5f
          x[(2 * DIMF + k) * DIMT + f] = (bi[k] + bi[m]) * 0.5f
          x[(3 * DIMF + k) * DIMT + f] = (br[m] - br[k]) * 0.5f
        }
      }
    }
  }

  private fun wave(y: FloatArray, oL: FloatArray, oR: FloatArray) {
    val len = H * (DIMT - 1) + nfft
    val aL = FloatArray(len); val aR = FloatArray(len); val ws = FloatArray(len)
    for (f in 0 until DIMT) {
      java.util.Arrays.fill(br, 0f); java.util.Arrays.fill(bi, 0f)
      for (k in 0 until DIMF) {
        val xlr = y[(0 * DIMF + k) * DIMT + f]; val xli = if (k == 0) 0f else y[(1 * DIMF + k) * DIMT + f]
        val xrr = y[(2 * DIMF + k) * DIMT + f]; val xri = if (k == 0) 0f else y[(3 * DIMF + k) * DIMT + f]
        br[k] = xlr - xri; bi[k] = xli + xrr
        if (k > 0) { br[nfft - k] = xlr + xri; bi[nfft - k] = -xli + xrr }
      }
      for (i in 0 until nfft) bi[i] = -bi[i]
      fft.fft(br, bi)
      for (i in 0 until nfft) {
        val w = win[i]
        aL[f * H + i] += br[i] / nfft * w; aR[f * H + i] += -bi[i] / nfft * w; ws[f * H + i] += w * w
      }
    }
    for (i in 0 until CH) { val j = i + trim; val d = maxOf(ws[j], 1e-8f); oL[i] = aL[j] / d; oR[i] = aR[j] / d }
  }

  private fun infer(s: OrtSession, x: FloatArray): FloatArray {
    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 4, DIMF.toLong(), DIMT.toLong()))
    try {
      s.run(mapOf("input" to t)).use { r ->
        val fb = (r.get(0) as OnnxTensor).floatBuffer
        val o = FloatArray(fb.remaining()); fb.get(o); return o
      }
    } finally { t.close() }
  }

  fun run(inFile: File, outFile: File, onP: (Float) -> Unit) {
    val total = inFile.length() / 8
    val so = OrtSession.SessionOptions(); so.setIntraOpNumThreads(4)
    val sess = env.createSession(model.absolutePath, so)
    try {
      val step = ((1f - overlap) * CH).toInt()
      val accL = FloatArray(CH); val accR = FloatArray(CH); val div = FloatArray(CH)
      val l = FloatArray(CH); val r = FloatArray(CH); val x = FloatArray(4 * DIMF * DIMT)
      val oL = FloatArray(CH); val oR = FloatArray(CH)
      val raw = ByteArray(CH * 8)
      val ob = ByteBuffer.allocate(CH * 8).order(ByteOrder.LITTLE_ENDIAN)
      var emitPos = 0L; var k = 0
      RandomAccessFile(inFile, "r").use { raf ->
        outFile.outputStream().use { fo ->
          while (true) {
            val start = k.toLong() * step
            java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
            val lo = start - trim
            val s0 = maxOf(0L, lo); val s1 = minOf(total, lo + CH)
            if (s1 > s0) {
              val n = (s1 - s0).toInt(); raf.seek(s0 * 8); raf.readFully(raw, 0, n * 8)
              val bb = ByteBuffer.wrap(raw, 0, n * 8).order(ByteOrder.LITTLE_ENDIAN); val off = (s0 - lo).toInt()
              for (i in 0 until n) { l[off + i] = bb.getFloat(); r[off + i] = bb.getFloat() }
            }
            spec(l, r, x)
            wave(infer(sess, x), oL, oR)
            for (i in 0 until CH) { accL[i] += oL[i] * hann[i]; accR[i] += oR[i] * hann[i]; div[i] += hann[i] }
            val last = start + CH >= trim.toLong() + total + trim
            val emit = if (last) CH else step
            ob.clear()
            for (i in 0 until emit) {
              val si = emitPos + i - trim
              if (si >= 0 && si < total) { val d = maxOf(div[i], 1e-8f); ob.putFloat(accL[i] / d); ob.putFloat(accR[i] / d) }
            }
            fo.write(ob.array(), 0, ob.position())
            if (emit < CH) {
              System.arraycopy(accL, emit, accL, 0, CH - emit); java.util.Arrays.fill(accL, CH - emit, CH, 0f)
              System.arraycopy(accR, emit, accR, 0, CH - emit); java.util.Arrays.fill(accR, CH - emit, CH, 0f)
              System.arraycopy(div, emit, div, 0, CH - emit); java.util.Arrays.fill(div, CH - emit, CH, 0f)
            }
            emitPos += emit; k++
            onP(minOf(1f, maxOf(0f, (emitPos - trim).toFloat() / total)))
            if (last) break
          }
        }
      }
    } finally { sess.close() }
  }
}
