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

var engine by mutableStateOf("loading")

fun initEngine(app: Application) {
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
  var opt by remember { mutableStateOf("MP3") }
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
    busy = true; st = "…"
    Thread {
      try {
        val i = YoutubeDL.getInstance().getInfo(url.trim())
        heights = (i.formats ?: arrayListOf()).filter { (it.vcodec ?: "none") != "none" && it.height > 0 }.map { it.height }.distinct().sortedDescending()
        opt = if (heights.isEmpty()) "MP3" else "${heights.first()}p"
        finish(i.title ?: "")
      } catch (e: Throwable) { finish("✗ ${e.message}") }
    }.start()
  }, enabled = ok && url.isNotBlank()) { Text(t("جلب الجودات", "Fetch qualities")) }
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    (heights.map { "${it}p" } + listOf("MP3", "M4A")).forEach { o -> Opt(o, opt == o) { opt = o } }
  }
  if (opt == "MP3" || opt == "M4A") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(128, 192, 256, 320).forEach { b -> Opt("$b", br == b) { br = b } } }
  Button({
    busy = true; pr = 0f; st = "…"
    val dir = tmp()
    val rq = YoutubeDLRequest(url.trim()).addOption("--no-playlist").addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
    if (opt == "MP3" || opt == "M4A") { rq.addOption("-x"); rq.addOption("--audio-format", opt.lowercase()); rq.addOption("--audio-quality", "${br}K") }
    else { val h = opt.removeSuffix("p"); rq.addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]"); rq.addOption("--merge-output-format", "mp4") }
    runJob(rq, dir, null, { pr = it / 100f }) { finish(it) }
  }, Modifier.fillMaxWidth(), enabled = ok && url.isNotBlank()) { Text(t("نزّل", "Download")) }
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
    busy = true; pr = 0f; st = "…"
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
        var src = inF
        if (af.isNotEmpty()) {
          val w = File(dir, "w"); w.mkdirs()
          val r1 = YoutubeDLRequest("file://" + inF.absolutePath).addOption("--enable-file-urls").addOption("--no-playlist")
          r1.addOption("-x"); r1.addOption("--audio-format", "wav")
          r1.addOption("--postprocessor-args", "ExtractAudio:-af " + af.joinToString(","))
          r1.addOption("-o", w.absolutePath + "/in_p1.%(ext)s")
          ytRun(r1) { pr = it / 200f }
          src = w.listFiles()!!.first()
        }
        val rq = YoutubeDLRequest("file://" + src.absolutePath).addOption("--enable-file-urls").addOption("--no-playlist")
        rq.addOption("-x"); rq.addOption("--audio-format", fmt); rq.addOption("--audio-quality", "${br}K")
        rq.addOption("-o", outDir.absolutePath + "/" + stem + ".%(ext)s")
        runJob(rq, dir, cover, { pr = 0.5f + it / 200f }) { finish(it) }
      } catch (e: Throwable) { finish("✗ ${e.message}"); dir.deleteRecursively() }
    }.start()
  }, Modifier.fillMaxWidth(), enabled = ok && fileUri != null && (doExtract || doBoost || doIso)) { Text(t("حوّل", "Convert")) }
  if (busy) LinearProgressIndicator(progress = { pr }, Modifier.fillMaxWidth(), color = ac)
  if (st.isNotEmpty()) Text(st)
}
