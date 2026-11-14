package com.huss.shear
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import java.io.ByteArrayInputStream
import java.io.InputStream
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.animation.core.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

var wl: WifiManager.WifiLock? = null

@Suppress("DEPRECATION")
fun fastLock(c: Context, on: Boolean) {
  try {
    if (on) {
      if (wl == null) wl = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "huss").also { it.setReferenceCounted(false) }
      wl?.acquire()
    } else wl?.let { if (it.isHeld) it.release() }
  } catch (e: Exception) {}
}

fun devName(c: Context): String = c.getSharedPreferences("huss", Context.MODE_PRIVATE).getString("name", Build.MODEL) ?: Build.MODEL
fun setDevName(c: Context, n: String) { c.getSharedPreferences("huss", Context.MODE_PRIVATE).edit().putString("name", n).apply() }

fun folderItems(c: Context, tree: Uri, docId: String = DocumentsContract.getTreeDocumentId(tree), pre: String = ""): List<Item> {
  val out = ArrayList<Item>()
  val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
  c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
    while (it.moveToNext()) {
      val id = it.getString(0); val n = it.getString(1)
      if (it.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) { out.addAll(folderItems(c, tree, id, "$pre$n/")); continue }
      val u = DocumentsContract.buildDocumentUriUsingTree(tree, id)
      out.add(Item(pre + n, it.getLong(2), u.toString()) { c.contentResolver.openInputStream(u)!! })
    }
  }
  return out
}

fun loadContacts(c: Context): List<Pair<String, String>> {
  val out = ArrayList<Pair<String, String>>(); val seen = HashSet<String>()
  c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use {
    while (it.moveToNext()) { val n = it.getString(0) ?: continue; val p = it.getString(1) ?: continue; if (seen.add(p)) out.add(n to p) }
  }
  return out
}

@Composable
fun ContactsTab(sel: List<Item>, toggle: (String, String, Long, () -> InputStream) -> Unit, ac: Color, ar: Boolean) {
  val c = LocalContext.current
  var ok by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
  val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok = it }
  val list = remember(ok) { if (ok) loadContacts(c) else listOf() }
  if (!ok) Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
    Button({ ask.launch(Manifest.permission.READ_CONTACTS) }) { Text(if (ar) "اسمح بالوصول لجهات الاتصال" else "Allow contacts access") }
  } else LazyColumn(Modifier.fillMaxSize()) {
    items(list) { (n, p) ->
      val k = "c:$p"
      Row(Modifier.fillMaxWidth().clickable {
        val b = "BEGIN:VCARD\nVERSION:3.0\nFN:$n\nTEL:$p\nEND:VCARD\n".toByteArray()
        toggle(k, "$n.vcf", b.size.toLong()) { ByteArrayInputStream(b) }
      }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(n, maxLines = 1); Text(p, fontSize = 12.sp) }
        Check(sel.any { it.key == k }, ac, Modifier)
      }
    }
  }
}

@Composable
fun ScanScreen(mod: Modifier, ar: Boolean, ac: Color, onBack: () -> Unit, onResult: (String) -> Unit) {
  val c = LocalContext.current
  var cam by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
  val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cam = it }
  LaunchedEffect(Unit) { if (!cam) ask.launch(Manifest.permission.CAMERA) }
  Box(mod.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
    if (cam) {
      val view = remember { DecoratedBarcodeView(c).also { it.setStatusText("") } }
      DisposableEffect(view) {
        view.resume()
        view.decodeSingle(object : BarcodeCallback {
          override fun barcodeResult(r: BarcodeResult) { r.text?.let(onResult) }
          override fun possibleResultPoints(p: MutableList<ResultPoint>?) {}
        })
        onDispose { view.pause() }
      }
      AndroidView({ view }, Modifier.fillMaxSize())
    } else Text(if (ar) "لازم تسمح بالكاميرا" else "Camera permission needed", Modifier.align(Alignment.Center), color = Color.White)
    Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("←", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { onBack() }); Spacer(Modifier.width(14.dp))
      Text("Scan", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Text(if (ar) "وجّه الكاميرا على الـ QR للاتصال" else "Align QR code within frame to connect", Modifier.align(Alignment.Center).padding(top = 260.dp), color = Color.White.copy(alpha = .8f))
    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color(0xFFF7F8FA), contentColor = Color.Black) {
      Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) { Pulse(if (ar) "بيدور على مرسل…" else "Searching for senders…", ac) }
    }
  }
}

@Composable
fun Pulse(text: String, ac: Color) {
  val a by rememberInfiniteTransition(label = "p").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(14.dp).scale(a).clip(CircleShape).background(ac)); Spacer(Modifier.width(8.dp)); Text(text)
  }
}

@Composable
fun SendPanel(ac: Color, ar: Boolean, status: String, img: Bitmap?, ssid: String, fast: Boolean, onFast: (Boolean) -> Unit, onName: () -> Unit) {
  val c = LocalContext.current
  var name by remember { mutableStateOf(devName(c)) }
  var edit by remember { mutableStateOf(false) }
  fun t(a: String, e: String) = if (ar) a else e
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(ac)) { Image(painterResource(R.drawable.logo), null, Modifier.fillMaxSize().scale(1.5f)) }
    Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { edit = true })
    if (ssid.isNotEmpty()) Text("Wi‑Fi: $ssid", fontSize = 13.sp)
    if (img != null) Image(img.asImageBitmap(), null, Modifier.size(260.dp)) else Text(status)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1A00B050)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("⚡ Ultra Fast Mode", Modifier.weight(1f), fontWeight = FontWeight.Bold)
      Switch(fast, onFast)
    }
    if (prog.connected) Text("✓ " + t("اتصل جهاز", "Receiver connected"), color = ac, fontWeight = FontWeight.Bold)
    else Pulse(t("بيدور على مستقبلين…", "Searching for receivers…"), ac)
    TransferBanner(ac, ar)
  }
  if (edit) {
    var tmp by remember { mutableStateOf(name) }
    AlertDialog(onDismissRequest = { edit = false }, text = { OutlinedTextField(tmp, { tmp = it }, singleLine = true) },
      confirmButton = { TextButton({ if (tmp.isNotBlank()) { name = tmp; setDevName(c, tmp); onName() }; edit = false }) { Text("OK") } })
  }
}
