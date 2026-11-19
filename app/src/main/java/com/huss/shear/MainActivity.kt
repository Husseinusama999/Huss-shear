package com.huss.shear
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) { super.onCreate(b); appCtx = applicationContext; initEngine(application); setContent { App() } }
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
  var addTab by remember { mutableIntStateOf(0) }
  var status by remember { mutableStateOf("") }
  val img = remember(payload, fast, tick) { payload?.let { qr(JSONObject(it).put("f", fast).put("n", devName(ctx)).toString()) } }
  fun t(a: String, e: String) = if (ar) a else e
  fun begin() {
    screen = "send"; payload = null; status = t("جاري تجهيز الاتصال…", "Preparing connection…")
    startHost(ctx, items.toList()) { b, e -> payload = b; status = if (b != null) t("امسح الكود من الجهاز التاني", "Scan this code on the other device") else "✗ $e" }
  }
  val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
    if (r.values.all { it }) begin() else status = t("لازم تسمح بالأذونات (الموقع)", "Please allow the permissions (location)")
  }
  val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> items = items + us.map { itemOf(ctx, it) } }
  val folderPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> u?.let { items = items + folderItems(ctx, it) } }
  fun onScan(txt: String) {
    try { val j = JSONObject(txt); screen = "recv"; status = t("جاري الاتصال… وافق على نافذة الاتصال", "Connecting… accept the connect prompt"); join(ctx, j) { s -> status = s } }
    catch (e: Exception) { status = "✗ QR"; screen = "home" }
  }
  LaunchedEffect(peerName) { if (peerName != null && (screen == "send" || screen == "recv")) screen = "portal" }
  BackHandler(enabled = screen != "home") {
    if (screen == "add") { items = listOf(); screen = "portal" } else { stopAll(ctx); items = listOf(); payload = null; screen = "home" }
  }
  val pm = ctx.packageManager
  val apps = remember {
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
      .map { it.activityInfo.applicationInfo }.filter { it.packageName != ctx.packageName }.distinctBy { it.packageName }
  }
  val dark = isSystemInDarkTheme()
  CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = ac) else lightColorScheme(primary = ac)) {
      Surface(Modifier.fillMaxSize()) {
        val noScroll = screen == "pick" || screen == "scan" || screen == "portal" || screen == "add"
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).then(if (noScroll) Modifier else Modifier.verticalScroll(rememberScrollState())), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          if (screen != "portal" && screen != "add") Row(verticalAlignment = Alignment.CenterVertically) {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
              Circle("♫", t("الصوت", "Audio"), ac) { status = ""; screen = "tools" }
              Circle("⇩", t("التنزيل", "Download"), ac) { status = ""; screen = "download" }
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
          if (screen == "pick") PickScreen(Modifier.weight(1f), items, { items = it }, { m -> pick.launch(m) }, { folderPick.launch(null) }, apps, pm, ac, ar) {
            perm.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
          }
          if (screen == "download") DownloadScreen(ac, ar)
          if (screen == "tools") ToolsScreen(ac, ar)
          if (screen == "scan") ScanScreen(Modifier.weight(1f), ar, ac, { stopAll(ctx); screen = "home" }) { onScan(it) }
          if (screen == "send") SendPanel(ac, ar, status, img, payload?.let { JSONObject(it).optString("s") } ?: "", fast, { fast = it; fastMode = it; fastLock(ctx, it) }, { tick++ })
          if (screen == "recv") Text(status, Modifier.fillMaxWidth(), fontSize = 16.sp)
          if (screen == "portal") PortalScreen(Modifier.weight(1f), ac, ar, { stopAll(ctx); items = listOf(); payload = null; screen = "home" }) { tab -> items = listOf(); addTab = tab; screen = "add" }
          if (screen == "add") PickScreen(Modifier.weight(1f), items, { items = it }, { m -> pick.launch(m) }, { folderPick.launch(null) }, apps, pm, ac, ar, addTab) {
            sendItems(items.toList()); items = listOf(); screen = "portal"
          }
        }
      }
    }
  }
}
