package com.huss.shear
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Res(val src: String, val title: String, val by: String, val dur: Long, val views: Long, val url: String, val thumb: String?, val verified: Boolean = false, var score: Double = 0.0)

fun httpGet(u: String): String {
  val c = URL(u).openConnection() as HttpURLConnection
  c.setRequestProperty("User-Agent", UA); c.connectTimeout = 20000; c.readTimeout = 20000
  return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
}

fun normWords(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.length > 1 }

/** Smart ranking (rule-based, not a language model): text match, official channels, popularity, duration fit, penalties for covers/remixes. */
fun rank(q: String, kind: Int, list: List<Res>): List<Res> {
  val qt = normWords(q).toSet(); val ql = q.lowercase()
  val bad = listOf("cover", "live", "remix", "karaoke", "reaction", "8d", "slowed", "nightcore", "sped up", "instrumental", "trailer", "كاريوكي", "ريمكس", "مقطع")
  list.forEach { r ->
    val tt = normWords(r.title + " " + r.by).toSet(); val t = r.title.lowercase()
    var s = if (qt.isEmpty()) 0.0 else qt.count { it in tt }.toDouble() / qt.size * 50
    if (r.verified) s += 12
    if (r.by.endsWith("- Topic") || Regex("vevo|official").containsMatchIn(r.by.lowercase())) s += 10
    if ("official" in t || "رسمي" in t) s += 6
    if (r.src == "yt") s += Math.log10((r.views + 10).toDouble()) * 2.5 else s += 5
    bad.forEach { w -> if (w in t && w !in ql) s -= 12 }
    if (kind == 0) { if (r.dur in 90..480) s += 8 else if (r.dur > 900) s -= 10 }
    if (kind == 2) { if (r.dur > 3600) s += 15 else if (r.src == "yt" && r.dur < 1200) s -= 15 }
    r.score = s
  }
  return list.sortedByDescending { it.score }
}

fun searchAll(q: String, kind: Int): List<Res> {
  val out = ArrayList<Res>()
  try {
    val yt = ServiceList.YouTube
    val h = yt.searchQHFactory.fromQuery(if (kind == 2) "$q full movie" else q, listOf("videos"), "")
    SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>().take(15).forEach {
      out.add(Res("yt", it.name, it.uploaderName ?: "", it.duration, it.viewCount, it.url, it.thumbnails.firstOrNull()?.url, it.isUploaderVerified))
    }
  } catch (e: Throwable) {}
  try {
    val u = "https://archive.org/advancedsearch.php?q=" + Uri.encode("($q) AND mediatype:(movies OR audio)") + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=8&output=json"
    val docs = JSONObject(httpGet(u)).getJSONObject("response").getJSONArray("docs")
    for (i in 0 until docs.length()) {
      val d = docs.getJSONObject(i); val id = d.getString("identifier")
      out.add(Res("ia", d.optString("title", id), d.optString("creator", ""), 0, 0, "https://archive.org/details/$id", "https://archive.org/services/img/$id"))
    }
  } catch (e: Throwable) {}
  return rank(q, kind, out)
}

fun iaPick(files: JSONArray): Pair<String?, String?> {
  var v: JSONObject? = null; var a: JSONObject? = null
  fun sz(f: JSONObject) = f.optString("size").toLongOrNull() ?: 0L
  for (i in 0 until files.length()) {
    val f = files.getJSONObject(i); val fmt = f.optString("format")
    if (fmt.contains("MPEG4") || fmt.contains("h.264")) { if (v == null || sz(f) > sz(v)) v = f }
    if (fmt.contains("MP3")) { if (a == null || sz(f) > sz(a)) a = f }
  }
  return v?.optString("name") to a?.optString("name")
}

fun iaJob(r: Res, vn: String?, an: String?, mp3: Boolean, br: Int, dir: File, onP: (Float) -> Unit, done: (String) -> Unit) {
  Thread {
    try {
      val id = r.url.substringAfterLast('/'); val base = "https://archive.org/download/$id/"
      val title = r.title.replace(Regex("[\\\\/:*?\"<>|%]"), "_").take(80)
      val out: File
      if (mp3 && an != null) { out = File(dir, "$title.mp3"); fetchFile(base + Uri.encode(an, "/"), out) { onP(it * 100f) } }
      else if (vn != null) {
        val v = File(dir, "v.bin"); fetchFile(base + Uri.encode(vn, "/"), v) { onP(it * (if (mp3) 80f else 100f)) }
        if (mp3) {
          out = File(dir, "$title.mp3")
          val x = runFfmpeg(listOf("-y", "-i", v.absolutePath, "-vn", "-c:a", "libmp3lame", "-b:a", "${br}k", out.absolutePath))
          if (x.first != 0) throw Exception(x.second.takeLast(300))
        } else { out = File(dir, "$title.mp4"); v.renameTo(out) }
      } else throw Exception("no file")
      exportFile(out); done("✓ Download/HussShear: " + out.name)
    } catch (e: Throwable) { done("✗ ${e.message}") }
    dir.deleteRecursively()
  }.start()
}

fun fmtDur(s: Long) = if (s <= 0) "" else "%d:%02d".format(s / 60, s % 60)

@Composable
fun RemoteThumb(url: String?, mod: Modifier) {
  val bmp by produceState<ImageBitmap?>(null, url) {
    value = if (url == null) null else withContext(Dispatchers.IO) { try { BitmapFactory.decodeStream(URL(url).openStream())?.asImageBitmap() } catch (e: Exception) { null } }
  }
  Box(mod.background(Color(0x22888888))) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

@Composable
fun DownloadScreen(ac: Color, ar: Boolean) {
  val c = LocalContext.current
  fun t(a: String, e: String) = if (ar) a else e
  var q by remember { mutableStateOf("") }
  var kind by remember { mutableIntStateOf(0) }
  var res by remember { mutableStateOf(listOf<Res>()) }
  var cur by remember { mutableStateOf<Res?>(null) }
  var heights by remember { mutableStateOf(listOf<Int>()) }
  var mp3 by remember { mutableStateOf(true) }
  var h by remember { mutableIntStateOf(0) }
  var br by remember { mutableIntStateOf(320) }
  var st by remember { mutableStateOf("") }
  var pr by remember { mutableFloatStateOf(0f) }
  var busy by remember { mutableStateOf(false) }
  var iaV by remember { mutableStateOf<String?>(null) }
  var iaA by remember { mutableStateOf<String?>(null) }

  fun pick(r: Res) {
    cur = r; heights = listOf(); h = 0; iaV = null; iaA = null; busy = true; st = t("بجلب الخيارات…", "Loading options…")
    Thread {
      try {
        if (r.src == "ia") {
          val p = iaPick(JSONObject(httpGet("https://archive.org/metadata/" + r.url.substringAfterLast('/'))).getJSONArray("files"))
          iaV = p.first; iaA = p.second; mp3 = iaV == null
        } else if (isYt(r.url)) {
          val i = StreamInfo.getInfo(r.url); ytInfo = i; heights = ytHeights(i); if (heights.isNotEmpty()) h = heights.first()
        } else {
          val i = YoutubeDL.getInstance().getInfo(r.url)
          heights = (i.formats ?: arrayListOf()).filter { (it.vcodec ?: "none") != "none" && it.height > 0 }.map { it.height }.distinct().sortedDescending()
          if (heights.isNotEmpty()) h = heights.first()
        }
        st = ""
      } catch (e: Throwable) { st = "✗ ${e.message}" }
      busy = false
    }.start()
  }

  Text(t("التنزيل", "Download"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
  OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text(t("اسم أغنية أو فيلم، أو الزق رابط", "Song or movie name, or paste a link")) }, singleLine = true)
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Opt(t("أغنية", "Song"), kind == 0) { kind = 0 }; Opt(t("فيديو", "Video"), kind == 1) { kind = 1 }; Opt(t("فيلم", "Movie"), kind == 2) { kind = 2 }
  }
  Button({
    val s = q.trim()
    if (s.startsWith("http")) pick(Res("link", s, "", 0, 0, s, null))
    else { busy = true; cur = null; res = listOf(); st = t("بيدور…", "Searching…"); Thread { try { res = searchAll(s, kind); st = if (res.isEmpty()) t("مفيش نتايج", "No results") else "" } catch (e: Throwable) { st = "✗ ${e.message}" }; busy = false }.start() }
  }, Modifier.fillMaxWidth(), enabled = q.isNotBlank() && !busy) { Text(t("ابحث", "Search")) }
  if (st.isNotEmpty()) Text(st)
  if (cur == null) res.forEachIndexed { i, r ->
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { pick(r) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
      RemoteThumb(r.thumb, Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(10.dp)))
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        if (i == 0) Text("⭐ " + t("الأفضل", "Best match"), color = ac, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(r.title, maxLines = 2, fontSize = 14.sp)
        Text(listOf(r.by, fmtDur(r.dur), if (r.src == "ia") t("Internet Archive (مجاني)", "Internet Archive (free)") else "YouTube").filter { it.isNotEmpty() }.joinToString(" • "), fontSize = 11.sp, maxLines = 1)
      }
    }
  }
  val r = cur
  if (r != null) {
    Text(r.title, fontWeight = FontWeight.Bold, maxLines = 2)
    OutlinedButton({ cur = null; st = "" }) { Text(t("رجوع للنتائج", "Back to results")) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Opt(t("صوت MP3", "Audio MP3"), mp3) { mp3 = true }; Opt(t("فيديو MP4", "Video MP4"), !mp3) { mp3 = false } }
    if (mp3) {
      if (r.src == "ia") Text(t("بتتنزل بجودتها الأصلية", "Downloads in original quality"), fontSize = 12.sp)
      else { Text(t("جودة الصوت (kbps)", "Audio quality (kbps)")); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(128, 192, 256, 320).forEach { b -> Opt("$b", br == b) { br = b } } } }
    } else if (r.src != "ia") {
      Text(t("جودة الفيديو", "Video quality"))
      if (heights.isEmpty()) Text(t("مفيش جودات متاحة", "No qualities available"), fontSize = 12.sp)
      else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { heights.forEach { x -> Opt("${x}p", h == x) { h = x } } }
    }
    Button({
      busy = true; pr = 0f; st = "…"
      val dir = File(c.cacheDir, "dl" + System.currentTimeMillis()).also { it.mkdirs() }
      val fin: (String) -> Unit = { st = it; busy = false }
      if (r.src == "ia") iaJob(r, iaV, iaA, mp3, br, dir, { pr = it / 100f }, fin)
      else if (isYt(r.url)) { val yi = ytInfo; if (yi != null) ytJob(yi, mp3, h, br, dir, { pr = it / 100f }, fin) else fin("✗ no info") }
      else linkJob(r.url, if (mp3) "MP3" else if (h > 0) "${h}p" else "best", br, dir, { pr = it / 100f }, fin)
    }, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("نزّل", "Download")) }
    if (busy) LinearProgressIndicator(progress = { pr }, Modifier.fillMaxWidth(), color = ac)
  }
  Text(t("نزّل المحتوى اللي ليك حق فيه بس. الترتيب الذكي هنا قواعد ترتيب مش نموذج ذكاء اصطناعي.", "Only download content you have the rights to. The smart ranking here is rule-based, not an AI model."), fontSize = 11.sp)
}
