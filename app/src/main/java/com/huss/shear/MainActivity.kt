package com.huss.shear
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

class Item(val name: String, val size: Long, val key: String = "", val open: () -> InputStream)

class Srv(val items: List<Item>) : NanoHTTPD(8888) {
  override fun serve(s: IHTTPSession): Response {
    if (s.uri == "/list") prog.connected = true
    if (s.uri == "/list") return newFixedLengthResponse(Response.Status.OK, "application/json",
      JSONArray(items.map { JSONObject().put("n", it.name).put("s", it.size) }).toString())
    val m = Regex("/f/(\\d+)").matchEntire(s.uri) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    val it = items[m.groupValues[1].toInt()]
    return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", CountIn(it.open()), it.size)
  }
}

var resv: WifiManager.LocalOnlyHotspotReservation? = null
var netCb: ConnectivityManager.NetworkCallback? = null
var server: Srv? = null

fun stopAll(c: Context) {
  fastLock(c, false)
  try { server?.stop() } catch (e: Exception) {}
  server = null
  try { resv?.close() } catch (e: Exception) {}
  resv = null
  netCb?.let { try { c.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) {} }
  netCb = null
}

fun itemOf(c: Context, u: Uri): Item {
  var n = "file"; var s = -1L
  c.contentResolver.query(u, null, null, null, null)?.use {
    if (it.moveToFirst()) { n = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)); s = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) }
  }
  return Item(n, s) { c.contentResolver.openInputStream(u)!! }
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

fun startSend(c: Context, items: List<Item>, done: (String?, String) -> Unit) {
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
            prog.reset(items.sumOf { it.size.coerceAtLeast(0L) }); val s = Srv(items); s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); server = s
            done(JSONObject().put("s", ssid).put("p", pass).put("u", "http://${hostIp()}:8888/").toString(), "")
          } catch (e: Exception) { done(null, e.message ?: "error") }
        }.start()
      }
      override fun onFailed(reason: Int) { done(null, "hotspot $reason") }
    }, Handler(Looper.getMainLooper()))
  } catch (e: Exception) { done(null, e.message ?: "error") }
}

fun receive(c: Context, n: Network, base: String, fast: Boolean, say: (String) -> Unit) {
  Thread {
    try {
      fastLock(c, fast)
      val a = JSONArray(String(n.openConnection(URL(base + "list")).getInputStream().readBytes()))
      prog.reset((0 until a.length()).sumOf { a.getJSONObject(it).getLong("s") })
      val pool = java.util.concurrent.Executors.newFixedThreadPool(if (fast) 3 else 1)
      val fin = java.util.concurrent.atomic.AtomicInteger(0)
      val err = java.util.concurrent.atomic.AtomicReference<String?>(null)
      val size0 = if (fast) 1048576 else 262144
      for (i in 0 until a.length()) pool.execute {
        try {
          val name = a.getJSONObject(i).getString("n")
          val dir = name.substringBeforeLast('/', "").replace("..", "_")
          val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name.substringAfterLast('/')); put(MediaStore.Downloads.RELATIVE_PATH, "Download/HussShear" + (if (dir.isEmpty()) "" else "/$dir")) }
          val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
          val ins = n.openConnection(URL(base + "f/$i")).getInputStream()
          val out = c.contentResolver.openOutputStream(u)!!
          val buf = ByteArray(size0); var k = ins.read(buf)
          while (k > 0) { out.write(buf, 0, k); synchronized(prog) { prog.done += k }; k = ins.read(buf) }
          out.close(); ins.close()
          say("${fin.incrementAndGet()}/${a.length()}")
        } catch (e: Exception) { err.set(e.message) }
      }
      pool.shutdown(); pool.awaitTermination(6, java.util.concurrent.TimeUnit.HOURS)
      say(if (err.get() == null) "✓ Download/HussShear" else "✗ ${err.get()}")
    } catch (e: Exception) { say("✗ ${e.message}") }
    fastLock(c, false)
  }.start()
}

fun join(c: Context, j: JSONObject, say: (String) -> Unit) {
  val cm = c.getSystemService(ConnectivityManager::class.java)
  val sp = WifiNetworkSpecifier.Builder().setSsid(j.getString("s")).setWpa2Passphrase(j.getString("p")).build()
  val rq = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(sp).build()
  val cb = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(n: Network) { receive(c, n, j.getString("u"), j.optBoolean("f"), say) }
    override fun onUnavailable() { say("✗ تعذر الاتصال / Connection failed") }
  }
  netCb = cb
  cm.requestNetwork(rq, cb, 40000)
}

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { App() } }
}

@Composable
fun Circle(sym: String, label: String, ac: Color, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(96.dp).clip(CircleShape).background(ac).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
      Text(sym, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
    }
    Spacer(Modifier.height(8.dp)); Text(label, fontWeight = FontWeight.Bold)
  }
}

@Composable
fun App() {
  val ctx = LocalContext.current
  var ar by remember { mutableStateOf(true) }
  var ac by remember { mutableStateOf(Color(0xFF7C5CFF)) }
  var screen by remember { mutableStateOf("home") }
  var items by remember { mutableStateOf(listOf<Item>()) }
  var payload by remember { mutableStateOf<String?>(null) }
  var fast by remember { mutableStateOf(false) }
  var tick by remember { mutableIntStateOf(0) }
  val img = remember(payload, fast, tick) { payload?.let { qr(JSONObject(it).put("f", fast).put("n", devName(ctx)).toString()) } }
  var status by remember { mutableStateOf("") }
  var showApps by remember { mutableStateOf(false) }
  fun t(a: String, e: String) = if (ar) a else e
  fun begin() {
    screen = "send"; payload = null; status = t("جاري تجهيز الاتصال…", "Preparing connection…")
    stopAll(ctx)
    startSend(ctx, items.toList()) { b, e -> payload = b; status = if (b != null) t("امسح الكود من الجهاز التاني", "Scan this code on the other device") else "✗ $e" }
  }
  val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
    if (r.values.all { it }) begin() else status = t("لازم تسمح بالأذونات (الموقع)", "Please allow the permissions (location)")
  }
  val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> items = items + us.map { itemOf(ctx, it) } }
  val folderPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> u?.let { items = items + folderItems(ctx, it) } }
  val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
    r.contents?.let {
      try { val j = JSONObject(it); screen = "recv"; status = t("جاري الاتصال… وافق على نافذة الاتصال", "Connecting… accept the connect prompt"); join(ctx, j) { s -> status = s } }
      catch (e: Exception) { status = "✗ QR" }
    }
  }
  fun onScan(txt: String) {
    try { val j = JSONObject(txt); screen = "recv"; status = t("جاري الاتصال… وافق على نافذة الاتصال", "Connecting… accept the connect prompt"); join(ctx, j) { s -> status = s } }
    catch (e: Exception) { status = "✗ QR"; screen = "home" }
  }
  BackHandler(enabled = screen != "home") { stopAll(ctx); items = listOf(); payload = null; screen = "home" }
  val pm = ctx.packageManager
  val apps = remember {
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
      .map { it.activityInfo.applicationInfo }.filter { it.packageName != ctx.packageName }.distinctBy { it.packageName }
  }
  val dark = isSystemInDarkTheme()
  CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = ac) else lightColorScheme(primary = ac)) {
      Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).then(if (screen == "pick" || screen == "scan") Modifier else Modifier.verticalScroll(rememberScrollState())), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(ac)) { Image(painterResource(R.drawable.logo), null, Modifier.fillMaxSize().scale(1.5f)) }
            Spacer(Modifier.width(12.dp))
            Text("Huss Shear", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Black)
            TextButton(onClick = { ar = !ar }) { Text(if (ar) "EN" else "ع") }
          }
          if (screen == "home") {
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
              Circle("↑", t("إرسال", "Send"), ac) { items = listOf(); status = ""; screen = "pick" }
              Circle("↓", t("استقبال", "Receive"), ac) { status = ""; screen = "scan" }
            }
            Spacer(Modifier.height(30.dp))
            if (status.isNotEmpty()) Text(status)
            Text(t("لون التطبيق", "App color"), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              listOf(0xFF7C5CFF, 0xFFFF5CA8, 0xFF00B8A9, 0xFFFF8A3D, 0xFF2F80ED, 0xFFE63946).forEach { c ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).clickable { ac = Color(c) })
              }
            }
          }
          if (screen == "scan") ScanScreen(Modifier.weight(1f), ar, ac, { stopAll(ctx); screen = "home" }) { onScan(it) }
          if (screen == "pick") PickScreen(Modifier.weight(1f), items, { items = it }, { m -> pick.launch(m) }, { folderPick.launch(null) }, apps, pm, ac, ar) {
            perm.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
          }
          if (screen == "send") SendPanel(ac, ar, status, img, payload?.let { JSONObject(it).optString("s") } ?: "", fast, { fast = it; fastLock(ctx, it) }, { tick++ })
          if (screen == "recv") TransferBanner(ac, ar)
          if (screen == "recv") Text(status, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 16.sp)
        }
        if (showApps) AlertDialog(onDismissRequest = { showApps = false }, confirmButton = { TextButton({ showApps = false }) { Text("OK") } },
          title = { Text(t("اختار تطبيقات", "Pick apps")) },
          text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
              apps.forEach { ai ->
                val label = pm.getApplicationLabel(ai).toString()
                TextButton({ items = items + Item("$label.apk", File(ai.sourceDir).length()) { FileInputStream(ai.sourceDir) } }) { Text(label) }
              }
            }
          })
      }
    }
  }
}
