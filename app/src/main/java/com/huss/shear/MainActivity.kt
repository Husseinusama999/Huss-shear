package com.huss.shear
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
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

class Item(val name: String, val size: Long, val open: () -> InputStream)

class Srv(val items: List<Item>) : NanoHTTPD(8888) {
  override fun serve(s: IHTTPSession): Response {
    if (s.uri == "/list") return newFixedLengthResponse(Response.Status.OK, "application/json",
      JSONArray(items.map { JSONObject().put("n", it.name).put("s", it.size) }).toString())
    val m = Regex("/f/(\\d+)").matchEntire(s.uri) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    val it = items[m.groupValues[1].toInt()]
    return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", it.open(), it.size)
  }
}

fun itemOf(c: Context, u: Uri): Item {
  var n = "file"; var s = -1L
  c.contentResolver.query(u, null, null, null, null)?.use {
    if (it.moveToFirst()) { n = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)); s = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) }
  }
  return Item(n, s) { c.contentResolver.openInputStream(u)!! }
}

fun localIp(): String {
  for (n in NetworkInterface.getNetworkInterfaces()) for (a in n.inetAddresses)
    if (!a.isLoopbackAddress && a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress!!
  return "0.0.0.0"
}

fun qr(t: String): Bitmap {
  val m = QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 640, 640)
  val b = Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565)
  for (x in 0 until 640) for (y in 0 until 640) b.setPixel(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
  return b
}

fun receive(c: Context, base: String, say: (String) -> Unit) {
  Thread {
    try {
      val b = if (base.endsWith("/")) base else "$base/"
      val a = JSONArray(URL(b + "list").readText())
      for (i in 0 until a.length()) {
        val name = a.getJSONObject(i).getString("n")
        say("${i + 1}/${a.length()}  $name")
        val v = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.RELATIVE_PATH, "Download/HussShear")
        }
        val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        URL(b + "f/$i").openStream().use { ins -> c.contentResolver.openOutputStream(u)!!.use { ins.copyTo(it, 262144) } }
      }
      say("✓ Download/HussShear")
    } catch (e: Exception) { say("✗ ${e.message}") }
  }.start()
}

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { App() } }
}

@Composable
fun App() {
  val ctx = LocalContext.current
  var ar by remember { mutableStateOf(true) }
  var ac by remember { mutableStateOf(Color(0xFF7C5CFF)) }
  var items by remember { mutableStateOf(listOf<Item>()) }
  var img by remember { mutableStateOf<Bitmap?>(null) }
  var url by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("") }
  var inp by remember { mutableStateOf("") }
  var showApps by remember { mutableStateOf(false) }
  var srv by remember { mutableStateOf<Srv?>(null) }
  fun t(a: String, e: String) = if (ar) a else e
  val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> items = items + us.map { itemOf(ctx, it) } }
  val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { inp = it; receive(ctx, it) { s -> status = s } } }
  val pm = ctx.packageManager
  val apps = remember {
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
      .map { it.activityInfo.applicationInfo }.filter { it.packageName != ctx.packageName }.distinctBy { it.packageName }
  }
  val dark = isSystemInDarkTheme()
  CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = ac) else lightColorScheme(primary = ac)) {
      Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(ac)) {
              Image(painterResource(R.drawable.logo), null, Modifier.fillMaxSize().scale(1.5f))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("Huss Shear", fontSize = 22.sp, fontWeight = FontWeight.Black)
              Text(t("شارك أي حاجة بسرعة وبدون إنترنت", "Share anything fast, offline"), fontSize = 12.sp)
            }
            TextButton(onClick = { ar = !ar }) { Text(if (ar) "EN" else "ع") }
          }
          Text(t("إرسال", "Send"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pick.launch(arrayOf("*/*")) }) { Text(t("ملفات / صور / فيديو", "Files / photos / video")) }
            OutlinedButton({ showApps = true }) { Text(t("تطبيقات", "Apps")) }
          }
          if (items.isNotEmpty()) Text(t("${items.size} ملف جاهز", "${items.size} item(s) ready") + ": " + items.joinToString { it.name })
          Button({
            srv?.stop(); val s = Srv(items.toList()); srv = s
            Thread { try { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } catch (e: Exception) { status = "✗ ${e.message}" } }.start()
            url = "http://${localIp()}:8888/"; img = qr(url)
          }, enabled = items.isNotEmpty()) { Text(t("ابدأ الإرسال (QR)", "Start sending (QR)")) }
          img?.let { Image(it.asImageBitmap(), null, Modifier.size(240.dp).align(Alignment.CenterHorizontally)); Text(url, Modifier.align(Alignment.CenterHorizontally)) }
          Text(t("استقبال", "Receive"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
          OutlinedTextField(inp, { inp = it }, Modifier.fillMaxWidth(), label = { Text(t("عنوان المُرسِل", "Sender address")) }, singleLine = true)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scan.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false)) }) { Text(t("امسح QR", "Scan QR")) }
            OutlinedButton({ receive(ctx, inp) { status = it } }, enabled = inp.isNotBlank()) { Text(t("اتصل", "Connect")) }
          }
          Text(status)
          Text(t("لون التطبيق", "App color"), fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0xFF7C5CFF, 0xFFFF5CA8, 0xFF00B8A9, 0xFFFF8A3D, 0xFF2F80ED, 0xFFE63946).forEach { c ->
              Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).clickable { ac = Color(c) })
            }
          }
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
