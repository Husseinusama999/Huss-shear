package com.huss.shear
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLConnection

class Item(val name: String, val size: Long, val key: String = "", val icon: ByteArray? = null, val open: () -> InputStream)

class Task(val item: Item, val mine: Boolean, val icon: ImageBitmap?) {
  var done by mutableLongStateOf(0L)
  var state by mutableIntStateOf(0)
  fun fin() = state != 0 || (item.size > 0 && done >= item.size)
}

lateinit var appCtx: Context
val tasks = mutableStateListOf<Task>()
val outbox = ArrayList<Task>()
var peerName by mutableStateOf<String?>(null)
var peerBase: String? = null
var net: Network? = null
var server: NanoHTTPD? = null
var resv: WifiManager.LocalOnlyHotspotReservation? = null
var netCb: ConnectivityManager.NetworkCallback? = null
var fastMode = false
var sess = 0
var pollSess = -1
var t0 = 0L
var base0 = 0L
val lock = Any()

fun stopAll(c: Context) {
  sess++; fastLock(c, false)
  try { server?.stop() } catch (e: Exception) {}
  server = null
  try { resv?.close() } catch (e: Exception) {}
  resv = null
  netCb?.let { try { c.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) {} }
  netCb = null; net = null; peerBase = null; peerName = null
  tasks.clear(); synchronized(outbox) { outbox.clear() }
}

fun conn(u: String): URLConnection = net?.openConnection(URL(u)) ?: URL(u).openConnection()

fun addTask(t: Task) {
  if (tasks.all { it.fin() }) { t0 = System.currentTimeMillis(); base0 = tasks.sumOf { it.done } }
  tasks.add(t)
}

fun iconOf(b: ByteArray?): ImageBitmap? = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

fun sendItems(list: List<Item>) {
  list.forEach { i -> val t = Task(i, true, iconOf(i.icon)); synchronized(outbox) { outbox.add(t) }; addTask(t) }
}

fun saveTo(t: Task, ins: InputStream) {
  var u: Uri? = null
  try {
    val name = t.item.name; val dir = name.substringBeforeLast('/', "").replace("..", "_")
    val v = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, name.substringAfterLast('/'))
      put(MediaStore.Downloads.RELATIVE_PATH, "Download/HussShear" + (if (dir.isEmpty()) "" else "/$dir"))
    }
    u = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
    val out = appCtx.contentResolver.openOutputStream(u)!!
    val buf = ByteArray(if (fastMode) 1048576 else 262144); var k = ins.read(buf)
    while (k > 0 && t.state != 2) { out.write(buf, 0, k); t.done += k; k = ins.read(buf) }
    out.close(); ins.close()
    if (t.state == 2 || (t.item.size > 0 && t.done < t.item.size)) { appCtx.contentResolver.delete(u, null, null); t.state = 2 } else t.state = 1
  } catch (e: Exception) {
    t.state = 2; u?.let { try { appCtx.contentResolver.delete(it, null, null) } catch (x: Exception) {} }
  }
}

fun upload(t: Task) {
  try {
    val c = conn(peerBase + "up?name=" + Uri.encode(t.item.name) + "&size=" + t.item.size) as HttpURLConnection
    c.requestMethod = "PUT"; c.doOutput = true; c.setFixedLengthStreamingMode(t.item.size)
    c.outputStream.use { o ->
      t.item.open().use { ins ->
        val buf = ByteArray(if (fastMode) 1048576 else 262144); var k = ins.read(buf)
        while (k > 0 && t.state != 2) { o.write(buf, 0, k); t.done += k; k = ins.read(buf) }
      }
    }
    if (c.responseCode == 200 && t.state != 2) t.state = 1 else t.state = 2
  } catch (e: Exception) { t.state = 2 }
}

class CountIn(val i: InputStream, val t: Task) : InputStream() {
  override fun read(): Int { if (t.state == 2) return -1; val b = i.read(); if (b >= 0) t.done += 1; return b }
  override fun read(b: ByteArray, o: Int, l: Int): Int { if (t.state == 2) return -1; val n = i.read(b, o, l); if (n > 0) t.done += n; return n }
  override fun close() = i.close()
}

class Srv : NanoHTTPD(8888) {
  override fun serve(s: IHTTPSession): Response {
    val u = s.uri
    if (u == "/hello") { peerName = s.parms["name"] ?: "device"; return newFixedLengthResponse("ok") }
    if (u == "/list") {
      val a = JSONArray(synchronized(outbox) {
        outbox.map { JSONObject().put("n", it.item.name).put("s", it.item.size).put("i", it.item.icon?.let { b -> Base64.encodeToString(b, Base64.NO_WRAP) } ?: "") }
      })
      return newFixedLengthResponse(Response.Status.OK, "application/json", a.toString())
    }
    if (u == "/up" && s.method == Method.PUT) {
      val size = (s.parms["size"] ?: "0").toLong()
      val t = Task(Item(s.parms["name"] ?: "file", size) { throw IllegalStateException() }, false, null)
      addTask(t)
      val files = HashMap<String, String>(); s.parseBody(files)
      val tmp = files["content"]
      if (tmp != null) { saveTo(t, FileInputStream(tmp)); File(tmp).delete() } else t.state = 2
      return newFixedLengthResponse("ok")
    }
    val m = Regex("/f/(\\d+)").matchEntire(u) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    val t = synchronized(outbox) { outbox[m.groupValues[1].toInt()] }
    return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", CountIn(t.item.open(), t), t.item.size)
  }
}

fun startPoll() {
  synchronized(lock) { if (pollSess == sess) return; pollSess = sess }
  val me = sess
  Thread {
    var next = 0; var up = 0
    val pool = java.util.concurrent.Executors.newFixedThreadPool(if (fastMode) 3 else 1)
    while (sess == me) {
      try {
        val b = peerBase
        if (b != null) {
          val a = JSONArray(String(conn(b + "list").getInputStream().readBytes()))
          while (next < a.length()) {
            val i = next++; val o = a.getJSONObject(i)
            val ic = o.optString("i").let { if (it.isEmpty()) null else Base64.decode(it, Base64.NO_WRAP) }
            val t = Task(Item(o.getString("n"), o.getLong("s"), "", ic) { throw IllegalStateException() }, false, iconOf(ic))
            addTask(t); pool.execute { saveTo(t, conn(b + "f/$i").getInputStream()) }
          }
          while (true) {
            val t = synchronized(outbox) { if (up < outbox.size) outbox[up++] else null } ?: break
            pool.execute { upload(t) }
          }
        }
      } catch (e: Exception) {}
      try { Thread.sleep(700) } catch (e: Exception) {}
    }
    pool.shutdownNow()
  }.start()
}

fun join(c: Context, j: JSONObject, say: (String) -> Unit) {
  stopAll(c); fastMode = j.optBoolean("f"); fastLock(c, fastMode)
  val cm = c.getSystemService(ConnectivityManager::class.java)
  val sp = WifiNetworkSpecifier.Builder().setSsid(j.getString("s")).setWpa2Passphrase(j.getString("p")).build()
  val rq = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(sp).build()
  val cb = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(n: Network) {
      Thread {
        try {
          net = n; peerBase = j.getString("u")
          conn(peerBase + "hello?name=" + Uri.encode(devName(c))).getInputStream().readBytes()
          peerName = j.optString("n", "device"); startPoll()
        } catch (e: Exception) { say("✗ ${e.message}") }
      }.start()
    }
    override fun onUnavailable() { say("✗ تعذر الاتصال / Connection failed") }
  }
  netCb = cb
  cm.requestNetwork(rq, cb, 40000)
}

fun hostIp(): String {
  val all = NetworkInterface.getNetworkInterfaces().toList()
  for (n in all) if (Regex("^(ap|swlan|wlan1|softap).*").matches(n.name))
    for (a in n.inetAddresses) if (a is Inet4Address) return a.hostAddress!!
  for (n in all) for (a in n.inetAddresses)
    if (!a.isLoopbackAddress && a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress!!
  return "192.168.43.1"
}

fun qr(t: String): Bitmap {
  val m = QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 640, 640)
  val b = Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565)
  for (x in 0 until 640) for (y in 0 until 640) b.setPixel(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
  return b
}

fun itemOf(c: Context, u: Uri): Item {
  var n = "file"; var s = -1L
  c.contentResolver.query(u, null, null, null, null)?.use {
    if (it.moveToFirst()) { n = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)); s = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) }
  }
  return Item(n, s) { c.contentResolver.openInputStream(u)!! }
}

fun startHost(c: Context, items: List<Item>, done: (String?, String) -> Unit) {
  stopAll(c); sendItems(items)
  val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  try {
    wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
      override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
        resv = r
        val ssid: String; val pass: String
        if (Build.VERSION.SDK_INT >= 30) { val k = r.softApConfiguration; ssid = k.ssid ?: ""; pass = k.passphrase ?: "" }
        else { @Suppress("DEPRECATION") val k = r.wifiConfiguration!!; ssid = k.SSID.trim('"'); pass = k.preSharedKey.trim('"') }
        Thread {
          try {
            Thread.sleep(800)
            val s = Srv(); s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); server = s
            done(JSONObject().put("s", ssid).put("p", pass).put("u", "http://${hostIp()}:8888/").toString(), "")
          } catch (e: Exception) { done(null, e.message ?: "error") }
        }.start()
      }
      override fun onFailed(reason: Int) { done(null, "hotspot $reason") }
    }, Handler(Looper.getMainLooper()))
  } catch (e: Exception) { done(null, e.message ?: "error") }
}

@Composable
fun Banner(ac: Color, ar: Boolean) {
  val total = tasks.sumOf { it.item.size.coerceAtLeast(0L) }
  if (total <= 0) return
  val done = tasks.sumOf { it.done }
  val el = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(1.0)
  val sp = (done - base0) / el
  val left = if (sp > 0) (total - done).coerceAtLeast(0L) / sp / 60 else 0.0
  val frac = (done.toFloat() / total).coerceIn(0f, 1f)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ac)) {
      Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color(0x40FFFFFF))) }
      Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Text("%.1f".format(done / 1e6), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(if (ar) "ميجا\nمُرسَل" else "MB\nSent", color = Color.White)
        Text("%.1f".format(left), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(if (ar) "دقيقة\nمتبقية" else "Min\nLeft", color = Color.White)
      }
    }
    Text(if (ar) "الإجمالي: ${mb(total)}" else "Total: ${mb(total)}", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
  }
}

@Composable
fun TaskCard(t: Task, ac: Color) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = if (t.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
    if (!t.mine) { Box(Modifier.size(36.dp).clip(CircleShape).background(ac)) { Image(painterResource(R.drawable.logo), null, Modifier.fillMaxSize().scale(1.5f)) }; Spacer(Modifier.width(8.dp)) }
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth(0.85f)) {
      Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.icon != null) Image(t.icon, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
        else Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(ac), contentAlignment = Alignment.Center) {
          Text(t.item.name.substringAfterLast('.', "FILE").take(4).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(t.item.name.substringAfterLast('/'), maxLines = 1, fontSize = 15.sp)
          Text(if (t.state == 2) "✗" else "${mb(t.done)}/${mb(t.item.size.coerceAtLeast(0L))}", fontSize = 12.sp)
          LinearProgressIndicator(progress = { if (t.item.size > 0) (t.done.toFloat() / t.item.size).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp), color = ac)
        }
        if (!t.fin()) Text("✕", Modifier.clickable { t.state = 2 }.padding(8.dp), fontSize = 18.sp)
        else if (t.state == 1 || t.done >= t.item.size) Text("✓", color = ac, modifier = Modifier.padding(8.dp), fontSize = 18.sp)
      }
    }
  }
}

@Composable
fun PortalScreen(mod: Modifier, ac: Color, ar: Boolean, onClose: () -> Unit, onAdd: (Int) -> Unit) {
  Column(mod) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("✕", Modifier.clickable { onClose() }.padding(8.dp), fontSize = 22.sp)
      Text("Portal", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
      Box(Modifier.size(34.dp).clip(CircleShape).background(ac), contentAlignment = Alignment.Center) { Text("2", color = Color.White, fontWeight = FontWeight.Bold) }
    }
    Spacer(Modifier.height(10.dp)); Banner(ac, ar)
    Text("${peerName ?: ""} " + (if (ar) "متصل" else "is online"), Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, fontSize = 13.sp)
    LazyColumn(Modifier.weight(1f)) { items(tasks) { TaskCard(it, ac) } }
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("▦" to 3, "🖼" to 1, "🎬" to 0, "🎵" to 2, "📄" to 4).forEach { (g, tab) ->
          Text(g, Modifier.clickable { onAdd(tab) }.padding(10.dp), fontSize = 26.sp)
        }
      }
    }
  }
}
