package com.huss.shear
import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.delay
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.VideoStream
import androidx.compose.ui.viewinterop.AndroidView
import org.schabi.newpipe.extractor.stream.DeliveryMethod
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

fun iaId(u: String) = u.substringAfter("/details/").substringBefore('/').substringBefore('?')

fun stripTags(x: String) = android.text.Html.fromHtml(x, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

/** General web search: Google Programmable Search if the user saved a key, otherwise DuckDuckGo (no key needed). */
fun searchWeb(q: String): List<Res> {
  val p = appCtx.getSharedPreferences("huss", 0)
  val gk = p.getString("gkey", "") ?: ""; val gx = p.getString("gcx", "") ?: ""
  val links = ArrayList<Triple<String, String, String?>>()
  if (gk.isNotBlank() && gx.isNotBlank()) {
    val items = JSONObject(httpGet("https://www.googleapis.com/customsearch/v1?key=" + Uri.encode(gk) + "&cx=" + Uri.encode(gx) + "&num=10&q=" + Uri.encode(q))).optJSONArray("items")
    if (items != null) for (i in 0 until items.length()) {
      val o = items.getJSONObject(i)
      links.add(Triple(o.optString("title"), o.getString("link"), o.optJSONObject("pagemap")?.optJSONArray("cse_thumbnail")?.optJSONObject(0)?.optString("src")))
    }
  } else {
    val page = httpGet("https://html.duckduckgo.com/html/?q=" + Uri.encode(q))
    Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(page).take(12).forEach { m ->
      val href = m.groupValues[1].replace("&amp;", "&")
      val u = Uri.parse(if (href.startsWith("//")) "https:$href" else href).getQueryParameter("uddg") ?: href
      links.add(Triple(stripTags(m.groupValues[2]), u, null))
    }
  }
  return links.map { (title, u, th) ->
    val host = try { URL(u).host.removePrefix("www.") } catch (e: Exception) { "" }
    if (u.contains("archive.org/details/")) Res("ia", title, host, 0, 0, u, "https://archive.org/services/img/" + iaId(u))
    else if (isYt(u)) Res("yt", title, host, 0, 0, u, th)
    else Res("web", title, host, 0, 0, u, th)
  }
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
  try { out.addAll(searchWeb(q).filter { w -> out.none { it.url == w.url } }) } catch (e: Throwable) {}
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

fun iaJob(r: Res, vn: String?, an: String?, mp3: Boolean, br: Int, dir: File, onP: (Float) -> Unit, done0: (String) -> Unit) {
  val job = Jobs.start(r.title)
  val done: (String) -> Unit = { job.finish(it.startsWith("✓"), it); done0(it) }
  Thread {
    try {
      val id = iaId(r.url); val base = "https://archive.org/download/$id/"
      val title = r.title.replace(Regex("[\\\\/:*?\"<>|%]"), "_").take(80)
      val out: File
      if (mp3 && an != null) { out = File(dir, "$title.mp3"); fetchFile(base + Uri.encode(an, "/"), out, { d, tt -> job.bytes(d, tt) }) { onP(it * 100f) } }
      else if (vn != null) {
        val v = File(dir, "v.bin"); fetchFile(base + Uri.encode(vn, "/"), v, { d, tt -> job.bytes(d, tt) }) { onP(it * (if (mp3) 80f else 100f)) }
        if (mp3) {
          out = File(dir, "$title.mp3")
          job.percent(-1f, "Converting / تحويل")
          val x = runFfmpeg(listOf("-y", "-i", v.absolutePath, "-vn", "-c:a", "libmp3lame", "-b:a", "${br}k", out.absolutePath), job)
          if (x.first != 0) throw Exception(x.second.takeLast(300))
        } else { out = File(dir, "$title.mp4"); v.renameTo(out) }
      } else throw Exception("no file")
      exportFile(out); done("✓ Download/HussShear: " + out.name)
    } catch (e: Throwable) { done("✗ ${e.message}") }
    dir.deleteRecursively()
  }.start()
}

class PrevSrc(val video: String?, val audio: String?)

fun previewUrl(r: Res): PrevSrc? {
  if (r.src == "ia") {
    val id = r.url.substringAfterLast('/')
    val p = iaPick(JSONObject(httpGet("https://archive.org/metadata/$id")).getJSONArray("files"))
    val n = p.first ?: p.second ?: return null
    return PrevSrc("https://archive.org/download/$id/" + Uri.encode(n, "/"), null)
  }
  if (isYt(r.url)) {
    val i = StreamInfo.getInfo(r.url)
    val all = ytStreams(i)
    val comb = all.filter { !it.isVideoOnly }.sortedBy { it.height }
    val c1 = comb.firstOrNull { it.height >= 240 } ?: comb.firstOrNull()
    if (c1 != null) return PrevSrc(c1.content, null)
    val vo = all.filter { it.isVideoOnly }.sortedWith(compareBy<VideoStream> { if (it.height >= 240) 0 else 1 }.thenBy { it.height }.thenByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 })
    val v = vo.firstOrNull(); val a = ytAudio(i)?.content
    return if (v != null) PrevSrc(v.content, a) else if (a != null) PrevSrc(null, a) else null
  }
  return null
}

@OptIn(UnstableApi::class)
@Composable
fun PreviewPlayer(src: PrevSrc, startFrac: Float, ac: Color, onErr: () -> Unit) {
  val ctx = LocalContext.current
  val player = remember(src) { ExoPlayer.Builder(ctx).build() }
  val tv = remember(src) { TextureView(ctx) }
  var ratio by remember { mutableFloatStateOf(16f / 9f) }
  var pos by remember { mutableLongStateOf(0L) }
  var dur by remember { mutableLongStateOf(0L) }
  var playing by remember { mutableStateOf(true) }
  DisposableEffect(player) {
    var seeked = false
    val http = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
    val pf = ProgressiveMediaSource.Factory(http)
    val vUrl = src.video; val aUrl = src.audio
    val ms: MediaSource = if (vUrl != null && aUrl != null) MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(vUrl)), pf.createMediaSource(MediaItem.fromUri(aUrl)))
      else pf.createMediaSource(MediaItem.fromUri((vUrl ?: aUrl)!!))
    val l = object : Player.Listener {
      override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) ratio = v.width * v.pixelWidthHeightRatio / v.height }
      override fun onPlayerError(e: PlaybackException) { onErr() }
      override fun onIsPlayingChanged(p: Boolean) { playing = p }
      override fun onPlaybackStateChanged(s: Int) {
        if (s == Player.STATE_READY && !seeked) { seeked = true; if (startFrac > 0f && player.duration > 600000) player.seekTo((player.duration * startFrac).toLong()) }
      }
    }
    player.addListener(l); player.setVideoTextureView(tv)
    player.setMediaSource(ms); player.prepare(); player.playWhenReady = true
    onDispose { player.release() }
  }
  LaunchedEffect(player) { while (true) { pos = player.currentPosition; dur = maxOf(0L, player.duration); delay(500) } }
  fun tm(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
  Column {
    if (src.video != null) AndroidView({ tv }, Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(if (playing) "⏸" else "▶", Modifier.clickable { if (player.isPlaying) player.pause() else player.play() }.padding(10.dp), fontSize = 22.sp, color = ac)
      Slider(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, { if (dur > 0) pos = (it * dur).toLong() }, Modifier.weight(1f), onValueChangeFinished = { player.seekTo(pos) })
      Text(tm(pos) + "/" + tm(dur), fontSize = 11.sp)
    }
  }
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
  var prevSrc by remember { mutableStateOf<PrevSrc?>(null) }
  var prevTitle by remember { mutableStateOf("") }
  var prevFrac by remember { mutableFloatStateOf(0f) }
  var prevBusy by remember { mutableStateOf(false) }

  fun preview(r: Res) {
    prevSrc = null; prevBusy = true; prevTitle = r.title
    Thread {
      try {
        val u = previewUrl(r)
        if (u == null) st = t("المعاينة مش متاحة للرابط ده", "Preview not available for this link")
        else { prevFrac = if (r.dur > 600 || kind == 2) 0.15f else 0f; prevSrc = u }
      } catch (e: Throwable) { st = "✗ ${e.message}" }
      prevBusy = false
    }.start()
  }
  var showCfg by remember { mutableStateOf(false) }
  val prefs = remember { c.getSharedPreferences("huss", 0) }
  var gk by remember { mutableStateOf(prefs.getString("gkey", "") ?: "") }
  var gx by remember { mutableStateOf(prefs.getString("gcx", "") ?: "") }

  fun pick(r: Res) {
    cur = r; heights = listOf(); h = 0; iaV = null; iaA = null; busy = true; st = t("بجلب الخيارات…", "Loading options…")
    Thread {
      try {
        if (r.src == "ia") {
          val p = iaPick(JSONObject(httpGet("https://archive.org/metadata/" + iaId(r.url))).getJSONArray("files"))
          iaV = p.first; iaA = p.second; mp3 = iaV == null
        } else if (isYt(r.url)) {
          val i = StreamInfo.getInfo(r.url); ytInfo = i; heights = ytHeights(i); if (heights.isNotEmpty()) h = heights.first()
        } else {
          val i = YoutubeDL.getInstance().getInfo(r.url)
          heights = (i.formats ?: arrayListOf()).filter { (it.vcodec ?: "none") != "none" && it.height > 0 }.map { it.height }.distinct().sortedDescending()
          if (heights.isNotEmpty()) h = heights.first()
        }
        st = ""
      } catch (e: Throwable) {
        val m = e.message ?: ""
        st = if (m.contains("Unsupported URL") || m.contains("No video")) t("مفيش فيديو قابل للتنزيل في الصفحة دي بالمحرك الحالي", "No downloadable video found on this page with the current engine") else "✗ $m"
      }
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
  TextButton({ showCfg = !showCfg }) { Text("⚙ " + t("بحث جوجل (اختياري)", "Google search (optional)")) }
  if (showCfg) {
    OutlinedTextField(gk, { gk = it; prefs.edit().putString("gkey", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Google API key") }, singleLine = true)
    OutlinedTextField(gx, { gx = it; prefs.edit().putString("gcx", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Search engine ID (cx)") }, singleLine = true)
    Text(t("من غير المفاتيح دي البحث بيشتغل بمحرك DuckDuckGo. جوجل محتاج مفتاح مجاني من Programmable Search Engine (100 بحث في اليوم).", "Without these keys, web search uses DuckDuckGo. Google needs a free Programmable Search Engine key (100 searches/day)."), fontSize = 11.sp)
  }
  if (st.isNotEmpty()) Text(st)
  if (prevBusy) Text(t("بجهّز المعاينة…", "Preparing preview…"), fontSize = 12.sp)
  val pu = prevSrc
  if (pu != null) {
    Text(prevTitle, fontWeight = FontWeight.Bold, maxLines = 1)
    PreviewPlayer(pu, prevFrac, ac) { st = t("المعاينة مش متاحة", "Preview unavailable") }
    OutlinedButton({ prevSrc = null }) { Text(t("إغلاق المعاينة", "Close preview")) }
  }
  if (cur == null) res.forEachIndexed { i, r ->
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { pick(r) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
      RemoteThumb(r.thumb, Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(10.dp)))
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        if (i == 0) Text("⭐ " + t("الأفضل", "Best match"), color = ac, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(r.title, maxLines = 2, fontSize = 14.sp)
        Text(listOf(r.by, fmtDur(r.dur), when (r.src) { "ia" -> t("Internet Archive (مجاني)", "Internet Archive (free)"); "web" -> t("ويب", "Web"); else -> "YouTube" }).filter { it.isNotEmpty() }.joinToString(" • "), fontSize = 11.sp, maxLines = 1)
      }
      Text("▶", Modifier.clickable { preview(r) }.padding(12.dp), fontSize = 22.sp, color = ac)
    }
  }
  val r = cur
  if (r != null) {
    Text(r.title, fontWeight = FontWeight.Bold, maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton({ cur = null; st = "" }) { Text(t("رجوع للنتائج", "Back to results")) }
      OutlinedButton({ preview(r) }) { Text("▶ " + t("معاينة", "Preview")) }
    }
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
  Text(t("النتايج من يوتيوب وInternet Archive وبحث الويب، من غير فلترة. التنزيل بيشتغل بس لو الصفحة فيها فيديو أو صوت المحرك بيفهمه. نزّل اللي ليك حق فيه بس. الترتيب هنا قواعد مش ذكاء اصطناعي.", "Results come from YouTube, Internet Archive and web search, unfiltered. Download only works when the page has video or audio the engine understands. Only download what you have rights to. Ranking is rule-based, not AI."), fontSize = 11.sp)
}
