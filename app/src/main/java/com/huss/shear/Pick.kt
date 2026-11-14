package com.huss.shear
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class Media(val uri: Uri, val name: String, val size: Long)

fun loadMedia(c: Context, base: Uri): List<Media> {
  val out = ArrayList<Media>()
  c.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
    null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use {
    while (it.moveToNext() && out.size < 300) out.add(Media(ContentUris.withAppendedId(base, it.getLong(0)), it.getString(1) ?: "file", it.getLong(2)))
  }
  return out
}

fun mb(n: Long) = if (n >= 1_000_000) "%.1fMB".format(n / 1e6) else "${n / 1000}KB"

@Composable
fun Check(on: Boolean, ac: Color, m: Modifier) {
  Box(m.size(22.dp).clip(CircleShape).background(if (on) ac else Color(0x88FFFFFF)), contentAlignment = Alignment.Center) {
    if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
fun Thumb(c: Context, m: Media, mod: Modifier) {
  val bmp by produceState<ImageBitmap?>(null, m.uri) {
    value = withContext(Dispatchers.IO) { try { c.contentResolver.loadThumbnail(m.uri, Size(240, 240), null).asImageBitmap() } catch (e: Exception) { null } }
  }
  Box(mod.background(Color(0x22888888))) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

@Composable
fun PickScreen(mod: Modifier, sel: List<Item>, onSel: (List<Item>) -> Unit, files: (Array<String>) -> Unit, folder: () -> Unit, apps: List<ApplicationInfo>,
               pm: PackageManager, ac: Color, ar: Boolean, startTab: Int = 0, next: () -> Unit) {
  val c = LocalContext.current
  fun t(a: String, e: String) = if (ar) a else e
  val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  var ok by remember { mutableStateOf(perms.all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }) }
  val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> ok = r.values.all { it } }
  var tab by remember { mutableIntStateOf(startTab) }
  val names = listOf(t("فيديوهات", "Videos"), t("صور", "Photos"), t("موسيقى", "Music"), t("تطبيقات", "Apps"), t("ملفات", "Files"), t("جهات الاتصال", "Contacts"))
  val lists = remember(ok) {
    if (ok) listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI).map { loadMedia(c, it) } else listOf()
  }
  fun toggle(k: String, n: String, s: Long, icon: ByteArray? = null, open: () -> InputStream) {
    onSel(if (sel.any { it.key == k }) sel.filter { it.key != k } else sel + Item(n, s, k, icon, open))
  }
  Column(mod) {
    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) { names.forEachIndexed { i, n -> Tab(tab == i, { tab = i }, text = { Text(n) }) } }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      if (tab == 4) {
        val cats = listOf("ALL" to arrayOf("*/*"), "PDF" to arrayOf("application/pdf"),
          "DOC" to arrayOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
          "EXCEL" to arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
          "PPT" to arrayOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
          "TXT" to arrayOf("text/plain"), "ZIP" to arrayOf("application/zip", "application/x-rar-compressed", "application/x-7z-compressed"))
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          items(cats) { (n, m) ->
            Column(Modifier.clickable { files(m) }, horizontalAlignment = Alignment.CenterHorizontally) {
              Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(ac), contentAlignment = Alignment.Center) { Text(n, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
              Text(n, fontSize = 11.sp)
            }
          }
          item {
            Column(Modifier.clickable { folder() }, horizontalAlignment = Alignment.CenterHorizontally) {
              Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(ac), contentAlignment = Alignment.Center) { Text("📁", fontSize = 24.sp) }
              Text(t("فولدرات", "Folders"), fontSize = 11.sp)
            }
          }
        }
      }
      else if (tab == 5) ContactsTab(sel, { k, n, s, o -> toggle(k, n, s, null, o) }, ac, ar)
      else if (tab < 3 && !ok) Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Button({ ask.launch(perms) }) { Text(t("اسمح بالوصول للوسائط", "Allow media access")) } }
      else if (tab == 3) LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(apps) { ai ->
          val label = remember(ai.packageName) { pm.getApplicationLabel(ai).toString() }
          val bm = remember(ai.packageName) { pm.getApplicationIcon(ai).toBitmap(96, 96) }
          val icon = remember(bm) { bm.asImageBitmap() }
          val png = remember(bm) { java.io.ByteArrayOutputStream().also { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
          val k = "app:" + ai.packageName
          Column(Modifier.clickable { toggle(k, "$label.apk", File(ai.sourceDir).length(), png) { FileInputStream(ai.sourceDir) } }, horizontalAlignment = Alignment.CenterHorizontally) {
            Box { Image(icon, null, Modifier.size(56.dp)); Check(sel.any { it.key == k }, ac, Modifier.align(Alignment.TopEnd)) }
            Text(label, fontSize = 11.sp, maxLines = 1)
          }
        }
      } else if (tab == 2) LazyColumn(Modifier.fillMaxSize()) {
        items(lists.getOrElse(2) { listOf() }) { m ->
          val k = m.uri.toString()
          Row(Modifier.fillMaxWidth().clickable { toggle(k, m.name, m.size) { c.contentResolver.openInputStream(m.uri)!! } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(m.name, maxLines = 1); Text(mb(m.size), fontSize = 12.sp) }
            Check(sel.any { it.key == k }, ac, Modifier)
          }
        }
      } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(lists.getOrElse(tab) { listOf() }) { m ->
          val k = m.uri.toString()
          Box(Modifier.aspectRatio(1f).clickable { toggle(k, m.name, m.size) { c.contentResolver.openInputStream(m.uri)!! } }) {
            Thumb(c, m, Modifier.fillMaxSize()); Check(sel.any { it.key == k }, ac, Modifier.align(Alignment.TopEnd).padding(6.dp))
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(t("${sel.size} عنصر مختار", "${sel.size} file(s) selected"), Modifier.weight(1f))
      Button(next, enabled = sel.isNotEmpty()) { Text(t("التالي", "NEXT")) }
    }
  }
}
