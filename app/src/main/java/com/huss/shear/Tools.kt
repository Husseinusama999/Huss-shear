package com.huss.shear
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

var engine by mutableStateOf("loading")

fun initEngine(app: Application) {
  try { NewPipe.init(Dl(), Localization.DEFAULT) } catch (e: Throwable) {}
  Thread { try { YoutubeDL.getInstance().init(app); FFmpeg.getInstance().init(app); engine = "ok" } catch (e: Throwable) { engine = "✗ ${e.message}" } }.start()
}

fun exportFile(f: File) {
  val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.RELATIVE_PATH, "Download/HussShear") }
  val u = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
  f.inputStream().use { i -> appCtx.contentResolver.openOutputStream(u)!!.use { i.copyTo(it, 262144) } }
}

fun withCover(mp3: File, img: ByteArray) {
  val mime = (if (img[0] == 0x89.toByte()) "image/png" else "image/jpeg").toByteArray()
  val body = java.io.ByteArrayOutputStream().apply { write(0); write(mime); write(0); write(3); write(0); write(img) }.toByteArray()
  val frame = java.io.ByteArrayOutputStream().apply { write("APIC".toByteArray()); write(java.nio.ByteBuffer.allocate(4).putInt(body.size).array()); write(0); write(0); write(body) }.toByteArray()
  val z = frame.size
  val hdr = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, ((z shr 21) and 127).toByte(), ((z shr 14) and 127).toByte(), ((z shr 7) and 127).toByte(), (z and 127).toByte())
  val old = mp3.readBytes()
  var off = 0
  if (old.size > 10 && old[0] == 0x49.toByte() && old[1] == 0x44.toByte() && old[2] == 0x33.toByte())
    off = 10 + (((old[6].toInt() and 127) shl 21) or ((old[7].toInt() and 127) shl 14) or ((old[8].toInt() and 127) shl 7) or (old[9].toInt() and 127))
  mp3.writeBytes(hdr + frame + old.copyOfRange(off, old.size))
}

fun ytRun(rq: YoutubeDLRequest, onP: (Float) -> Unit) { YoutubeDL.getInstance().execute(rq, "huss", { p, _, _ -> onP(p) }) }

fun runFfmpeg(args: List<String>): Pair<Int, String> {
  val bin = File(appCtx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
  if (!bin.exists()) return 127 to "ffmpeg binary not found: ${bin.absolutePath}"
  val libs = ArrayList<String>()
  for (r in listOf(appCtx.noBackupFilesDir, appCtx.filesDir, File(appCtx.applicationInfo.nativeLibraryDir), appCtx.codeCacheDir))
    if (r.exists()) r.walkTopDown().maxDepth(9).forEach { if (it.isFile && it.name.endsWith(".so") && it.parentFile!!.absolutePath !in libs) libs.add(it.parentFile!!.absolutePath) }
  val hasCpp = libs.any { File(it, "libc++_shared.so").exists() }
  val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner", "-nostats") + args).redirectErrorStream(true)
  pb.environment()["LD_LIBRARY_PATH"] = libs.joinToString(":")
  val p = pb.start(); val out = p.inputStream.bufferedReader().readText()
  val code = p.waitFor()
  return code to (if (code != 0) out + "\n[c++ shared found: $hasCpp; lib dirs: ${libs.size}]" else out)
}

fun linkJob(url: String, opt: String, br: Int, dir: File, onP: (Float) -> Unit, done: (String) -> Unit) {
  Thread {
    try {
      val audio = opt == "MP3" || opt == "M4A"
      val rq = YoutubeDLRequest(url).addOption("--no-playlist").addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
      if (audio) rq.addOption("-f", "ba/b")
      else { val h = opt.removeSuffix("p"); rq.addOption("-f", if (h == "best") "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b"); rq.addOption("--merge-output-format", "mp4") }
      var err: String? = null
      try { ytRun(rq, onP) } catch (e: Throwable) { err = e.message }
      val outs = ArrayList<File>()
      val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") } ?: listOf()
      if (audio) {
        val src = files.maxByOrNull { it.length() } ?: throw Exception(err ?: "no file")
        val out = File(dir, src.nameWithoutExtension + "." + opt.lowercase())
        val r = runFfmpeg(listOf("-y", "-i", src.absolutePath, "-vn", "-c:a", if (opt == "MP3") "libmp3lame" else "aac", "-b:a", "${br}k", out.absolutePath))
        if (r.first != 0) throw Exception(r.second.takeLast(300))
        outs.add(out)
      } else {
        val frag = files.filter { Regex("\\.f\\d+\\.").containsMatchIn(it.name) }.sortedByDescending { it.length() }
        if (frag.size >= 2) {
          val out = File(dir, frag[0].name.replace(Regex("\\.f\\d+\\."), ".").substringBeforeLast('.') + ".mp4")
          val r = runFfmpeg(listOf("-y", "-i", frag[0].absolutePath, "-i", frag[1].absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", out.absolutePath))
          if (r.first != 0) throw Exception(r.second.takeLast(300))
          outs.add(out)
        } else if (files.isNotEmpty() && err == null) outs.addAll(files) else throw Exception(err ?: "no file")
      }
      outs.forEach { exportFile(it) }
      done("✓ Download/HussShear: " + outs.joinToString { it.name })
    } catch (e: Throwable) { done("✗ ${e.message}") }
    dir.deleteRecursively()
  }.start()
}

var ytInfo: StreamInfo? = null
const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

class Dl : Downloader() {
  override fun execute(request: Request): Response {
    val c = URL(request.url()).openConnection() as HttpURLConnection
    c.requestMethod = request.httpMethod(); c.connectTimeout = 30000; c.readTimeout = 30000
    c.setRequestProperty("User-Agent", UA)
    request.headers().forEach { (k, v) -> c.setRequestProperty(k, v.joinToString(",")) }
    val body = request.dataToSend()
    if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
    val code = c.responseCode
    if (code == 429) throw ReCaptchaException("captcha", request.url())
    val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
    val hs = c.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value.toList() }
    return Response(code, c.responseMessage ?: "", hs, text, c.url.toString())
  }
}

fun isYt(u: String) = Regex("(youtube\\.com|youtu\\.be)").containsMatchIn(u)

fun ytStreams(i: StreamInfo): List<VideoStream> = (i.videoOnlyStreams + i.videoStreams).filter { it.height > 0 && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
fun ytHeights(i: StreamInfo): List<Int> = ytStreams(i).map { it.height }.distinct().sortedDescending()
fun ytVideo(i: StreamInfo, h: Int): VideoStream? = ytStreams(i).filter { it.height == h }.sortedByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 }.firstOrNull()
fun ytAudio(i: StreamInfo): AudioStream? = i.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
  .sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate }).firstOrNull()

fun fetchFile(url: String, out: File, onP: (Float) -> Unit) {
  var pos = 0L; var total = -1L; val ch = 8L * 1024 * 1024
  out.outputStream().use { o ->
    while (total < 0 || pos < total) {
      val c = URL(url).openConnection() as HttpURLConnection
      c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Range", "bytes=$pos-${pos + ch - 1}")
      c.connectTimeout = 30000; c.readTimeout = 30000
      val code = c.responseCode
      if (code != 200 && code != 206) throw Exception("HTTP $code")
      if (total < 0) total = if (code == 206) (c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L) else c.contentLengthLong
      var n = 0L; val buf = ByteArray(262144)
      c.inputStream.use { i -> var k = i.read(buf); while (k > 0) { o.write(buf, 0, k); n += k; onP(if (total > 0) ((pos + n).toFloat() / total).coerceIn(0f, 1f) else 0f); k = i.read(buf) } }
      pos += n
      if (code == 200 || n == 0L) break
    }
  }
}

fun ytJob(info: StreamInfo, mp3: Boolean, h: Int, br: Int, dir: File, onP: (Float) -> Unit, done: (String) -> Unit) {
  Thread {
    try {
      val title = info.name.replace(Regex("[\\\\/:*?\"<>|%]"), "_").take(80)
      val aud = ytAudio(info) ?: throw Exception("no audio stream")
      val out: File
      if (mp3) {
        val a = File(dir, "a.bin"); fetchFile(aud.content, a) { onP(it * 90f) }
        out = File(dir, "$title.mp3")
        val r = runFfmpeg(listOf("-y", "-i", a.absolutePath, "-vn", "-c:a", "libmp3lame", "-b:a", "${br}k", out.absolutePath))
        if (r.first != 0) throw Exception(r.second.takeLast(300))
      } else {
        val v = ytVideo(info, if (h > 0) h else (ytHeights(info).firstOrNull() ?: 0)) ?: throw Exception("no video stream")
        val vf = File(dir, "v.bin"); fetchFile(v.content, vf) { onP(it * 45f) }
        out = File(dir, "$title.mp4")
        val args = ArrayList<String>(listOf("-y", "-i", vf.absolutePath))
        if (v.isVideoOnly) {
          val af = File(dir, "a.bin"); fetchFile(aud.content, af) { onP(45f + it * 45f) }
          args.addAll(listOf("-i", af.absolutePath, "-map", "0:v:0", "-map", "1:a:0"))
        }
        args.addAll(listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "192k", out.absolutePath))
        val r = runFfmpeg(args)
        if (r.first != 0) throw Exception(r.second.takeLast(300))
      }
      exportFile(out)
      done("✓ Download/HussShear: " + out.name)
    } catch (e: Throwable) { done("✗ ${e.message}") }
    dir.deleteRecursively()
  }.start()
}

fun runJob(rq: YoutubeDLRequest, dir: File, cover: ByteArray?, onP: (Float) -> Unit, done: (String) -> Unit) {
  Thread {
    try {
      YoutubeDL.getInstance().execute(rq, "huss", { p, _, _ -> onP(p) })
      val outs = (dir.walkTopDown().filter { it.isFile && !it.name.startsWith("in_") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }).toList()
      if (cover != null) outs.filter { it.extension == "mp3" }.forEach { withCover(it, cover) }
      outs.forEach { exportFile(it) }
      done(if (outs.isEmpty()) "✗ no output" else "✓ Download/HussShear: " + outs.joinToString { it.name })
    } catch (e: Throwable) { done("✗ ${e.message}") }
    dir.deleteRecursively()
  }.start()
}

@Composable
fun Opt(label: String, on: Boolean, click: () -> Unit) { if (on) Button(click) { Text(label) } else OutlinedButton(click) { Text(label) } }

@Composable
fun ToolsScreen(ac: Color, ar: Boolean) {
  val c = LocalContext.current
  fun t(a: String, e: String) = if (ar) a else e
  var url by remember { mutableStateOf("") }
  var heights by remember { mutableStateOf(listOf<Int>()) }
  var kind by remember { mutableStateOf("MP3") }
  var h by remember { mutableIntStateOf(0) }
  var br by remember { mutableIntStateOf(320) }
  var pr by remember { mutableFloatStateOf(0f) }
  var st by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var fileUri by remember { mutableStateOf<Uri?>(null) }
  var fileName by remember { mutableStateOf("") }
  var fmt by remember { mutableStateOf("mp3") }
  var mode by remember { mutableStateOf("voc") }
  var doExtract by remember { mutableStateOf(true) }
  var doBoost by remember { mutableStateOf(false) }
  var doIso by remember { mutableStateOf(false) }
  var gain by remember { mutableFloatStateOf(100f) }
  var cover by remember { mutableStateOf<ByteArray?>(null) }
  val pickF = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { fileUri = u; fileName = itemOf(c, u).name } }
  val pickC = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) cover = c.contentResolver.openInputStream(u)?.use { it.readBytes() } }
  val ok = engine == "ok" && !busy
  fun tmp() = File(c.cacheDir, "dl" + System.currentTimeMillis()).also { it.mkdirs() }
  fun finish(m: String) { st = m; busy = false }
  Text(t("تنزيل من رابط", "Download from link"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
  if (engine != "ok") Text((if (engine == "loading") t("جاري تجهيز المحرك…", "Preparing engine…") else engine), fontSize = 12.sp)
  OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(t("رابط فيديو أو ملف صوت", "Video or audio link")) }, singleLine = true)
  Button({
    busy = true; st = "…"; ytInfo = null
    Thread {
      try {
        if (isYt(url)) { val i = StreamInfo.getInfo(url.trim()); ytInfo = i; heights = ytHeights(i); finish(i.name) }
        else {
          val i = YoutubeDL.getInstance().getInfo(url.trim())
          heights = (i.formats ?: arrayListOf()).filter { (it.vcodec ?: "none") != "none" && it.height > 0 }.map { it.height }.distinct().sortedDescending()
          finish(i.title ?: "")
        }
        if (heights.isNotEmpty()) h = heights.first()
      } catch (e: Throwable) { finish("✗ ${e.message}") }
    }.start()
  }, enabled = (ok || (!busy && isYt(url))) && url.isNotBlank()) { Text(t("جلب الجودات", "Fetch qualities")) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Opt("MP3", kind == "MP3") { kind = "MP3" }; Opt("MP4", kind == "MP4") { kind = "MP4" } }
  if (kind == "MP3") {
    Text(t("جودة الصوت (kbps)", "Audio quality (kbps)"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(128, 192, 256, 320).forEach { b -> Opt("$b", br == b) { br = b } } }
  } else {
    Text(t("جودة الفيديو", "Video quality"))
    if (heights.isEmpty()) Text(t("اضغط «جلب الجودات» الأول", "Tap “Fetch qualities” first"), fontSize = 12.sp)
    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { heights.forEach { x -> Opt("${x}p", h == x) { h = x } } }
  }
  Button({
    busy = true; pr = 0f; st = "…"
    val dir = tmp()
    val yi = ytInfo
    if (isYt(url)) {
      if (yi != null) ytJob(yi, kind == "MP3", h, br, dir, { pr = it / 100f }) { finish(it) }
      else Thread {
        try { val i = StreamInfo.getInfo(url.trim()); ytInfo = i; ytJob(i, kind == "MP3", h, br, dir, { pr = it / 100f }) { finish(it) } }
        catch (e: Throwable) { finish("✗ NewPipe: ${e.message}"); dir.deleteRecursively() }
      }.start()
    } else linkJob(url.trim(), if (kind == "MP3") "MP3" else if (h > 0) "${h}p" else "best", br, dir, { pr = it / 100f }) { finish(it) }
  }, Modifier.fillMaxWidth(), enabled = (ok || (!busy && isYt(url))) && url.isNotBlank()) { Text(t("نزّل", "Download")) }
  HorizontalDivider()
  Text(t("تحويل ملف لصوت", "Convert a file to audio"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
  OutlinedButton({ pickF.launch(arrayOf("video/*", "audio/*")) }) { Text(if (fileName.isEmpty()) t("اختار فيديو أو أغنية", "Pick a video or song") else fileName) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Opt("MP3", fmt == "mp3") { fmt = "mp3" }; Opt("M4A", fmt == "m4a") { fmt = "m4a" } }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(128, 192, 256, 320).forEach { b -> Opt("$b", br == b) { br = b } } }
  Row(verticalAlignment = Alignment.CenterVertically) { Text(t("استخراج الصوت", "Extract audio"), Modifier.weight(1f)); Switch(doExtract, { doExtract = it }) }
  Row(verticalAlignment = Alignment.CenterVertically) { Text(t("رفع الصوت", "Boost volume"), Modifier.weight(1f)); Switch(doBoost, { doBoost = it }) }
  if (doBoost) { Text("${gain.toInt()}%"); Slider(gain, { gain = it }, valueRange = 100f..300f) }
  Row(verticalAlignment = Alignment.CenterVertically) { Text(t("عزل (مغني / موسيقى)", "Isolate (vocals / music)"), Modifier.weight(1f)); Switch(doIso, { doIso = it }) }
  if (doIso) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Opt(t("صوت المغني بس", "Vocals only"), mode == "voc") { mode = "voc" }
    Opt(t("موسيقى بس", "Music only"), mode == "inst") { mode = "inst" }
  }
  if (fmt == "mp3") OutlinedButton({ pickC.launch("image/*") }) { Text(if (cover == null) t("صورة الغلاف (اختياري)", "Cover art (optional)") else t("تم اختيار الغلاف ✓", "Cover selected ✓")) }
  Button({
    val u = fileUri ?: return@Button
    busy = true; pr = -1f; st = "…"
    val dir = tmp(); val outDir = File(dir, "out")
    val stem = fileName.substringBeforeLast('.').replace("/", "_").replace("%", "%%")
    val inF = File(dir, "in_" + System.currentTimeMillis() + "." + fileName.substringAfterLast('.', "bin"))
    Thread {
      try {
        c.contentResolver.openInputStream(u)!!.use { i -> inF.outputStream().use { i.copyTo(it, 262144) } }
        val af = ArrayList<String>()
        if (doIso && mode == "inst") af.add("pan=stereo|c0=c0-c1|c1=c1-c0")
        if (doIso && mode == "voc") af.add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1")
        if (doBoost && gain.toInt() != 100) { af.add("volume=" + String.format(Locale.US, "%.2f", gain / 100f)); af.add("alimiter=limit=0.97") }
        if (doIso) af.add(0, "aformat=channel_layouts=stereo")
        outDir.mkdirs()
        val outF = File(outDir, stem + "." + fmt)
        val cv = cover?.let { File(dir, "cover.jpg").also { f -> f.writeBytes(it) } }
        val a = ArrayList<String>(listOf("-y", "-i", inF.absolutePath))
        if (cv != null) a.addAll(listOf("-i", cv.absolutePath))
        a.addAll(listOf("-map", "0:a:0"))
        if (cv != null) a.addAll(listOf("-map", "1:v:0"))
        if (af.isNotEmpty()) a.addAll(listOf("-af", af.joinToString(",")))
        a.addAll(listOf("-c:a", if (fmt == "mp3") "libmp3lame" else "aac", "-b:a", "${br}k"))
        if (cv != null) { a.addAll(listOf("-c:v", "mjpeg", "-disposition:v:0", "attached_pic")); if (fmt == "mp3") a.addAll(listOf("-id3v2_version", "3")) }
        a.add(outF.absolutePath)
        val r = runFfmpeg(a)
        if (r.first != 0 || !outF.exists()) finish("✗ " + r.second.takeLast(300))
        else { exportFile(outF); finish("✓ Download/HussShear: " + outF.name) }
        dir.deleteRecursively()
      } catch (e: Throwable) { finish("✗ ${e.message}"); dir.deleteRecursively() }
    }.start()
  }, Modifier.fillMaxWidth(), enabled = ok && fileUri != null && (doExtract || doBoost || doIso)) { Text(t("حوّل", "Convert")) }
  if (busy) { if (pr < 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ac) else LinearProgressIndicator(progress = { pr }, Modifier.fillMaxWidth(), color = ac) }
  if (st.isNotEmpty()) Text(st)
}
