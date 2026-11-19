package com.huss.shear
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val CH_PROG = "jobs_progress"
const val CH_DONE = "jobs_done"

fun setupChannels(c: Context) {
  val nm = c.getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel(CH_PROG, "Progress / التقدم", NotificationManager.IMPORTANCE_LOW))
  nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished / الانتهاء", NotificationManager.IMPORTANCE_DEFAULT))
}

class CancelReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) { Jobs.cancel(i.getIntExtra("id", -1)) }
}

/** Keeps the app process alive (foreground service) while downloads/conversions run. */
class KeepAlive : Service() {
  override fun onBind(i: Intent?): IBinder? = null
  override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
    val n = Notification.Builder(this, CH_PROG).setSmallIcon(R.drawable.logo).setContentTitle("Huss Shear")
      .setContentText("شغال في الخلفية / Working in background").setOngoing(true).build()
    startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    return START_NOT_STICKY
  }
}

object Jobs {
  private val ids = AtomicInteger(100)
  private val active = AtomicInteger(0)
  private var wl: PowerManager.WakeLock? = null
  private val map = ConcurrentHashMap<Int, JobH>()
  fun cancel(id: Int) { map[id]?.cancel() }
  fun forget(id: Int) { map.remove(id) }

  fun start(title: String): JobH {
    if (active.getAndIncrement() == 0) {
      try { ContextCompat.startForegroundService(appCtx, Intent(appCtx, KeepAlive::class.java)) } catch (e: Exception) {}
      try { wl = (appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "huss:job").also { it.acquire(3 * 60 * 60 * 1000L) } } catch (e: Exception) {}
    }
    val j = JobH(ids.incrementAndGet(), title); map[j.id] = j
    return j
  }

  fun release() {
    if (active.decrementAndGet() <= 0) {
      active.set(0)
      try { wl?.release() } catch (e: Exception) {}
      wl = null
      try { appCtx.stopService(Intent(appCtx, KeepAlive::class.java)) } catch (e: Exception) {}
    }
  }
}

class JobH(val id: Int, val title: String) {
  private var last = 0L
  @Volatile var cancelled = false
  @Volatile var proc: Process? = null
  @Volatile var onCancel: (() -> Unit)? = null
  private fun chk() { if (cancelled) throw CancellationException("cancelled") }
  private fun cancelPi() = PendingIntent.getBroadcast(appCtx, id, Intent(appCtx, CancelReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  fun cancel() {
    cancelled = true
    try { proc?.destroy() } catch (e: Exception) {}
    try { onCancel?.invoke() } catch (e: Exception) {}
    post("جاري الإلغاء… / Cancelling…", 0, true, true)
  }
  private val nm = appCtx.getSystemService(NotificationManager::class.java)
  private fun pi() = PendingIntent.getActivity(appCtx, 0, Intent(appCtx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  private fun post(text: String, pct: Int, indet: Boolean, force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - last < 700) return
    last = now
    val b = Notification.Builder(appCtx, CH_PROG).setSmallIcon(R.drawable.logo).setContentTitle(title).setContentText(text)
      .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi()).setProgress(100, pct, indet)
      .addAction(Notification.Action.Builder(Icon.createWithResource(appCtx, R.drawable.logo), "إلغاء / Cancel", cancelPi()).build())
    try { nm.notify(id, b.build()) } catch (e: Exception) {}
  }

  /** Downloaded bytes out of total bytes (total <= 0 means unknown). */
  fun bytes(done: Long, total: Long, label: String = "") {
    chk()
    val l = if (label.isEmpty()) "" else " • $label"
    if (total > 0) { val p = (done * 100 / total).toInt().coerceIn(0, 100); post("$p% • ${mb(done)} / ${mb(total)}$l", p, false) }
    else post(mb(done) + l, 0, true)
  }

  /** Percent 0..1, or a negative value for "working, no percentage". */
  fun percent(p: Float, label: String = "") {
    chk()
    val l = if (label.isEmpty()) "" else " • $label"
    if (p < 0f) post(label.ifEmpty { "..." }, 0, true, true)
    else { val x = (p * 100).toInt().coerceIn(0, 100); post("$x%$l", x, false) }
  }

  fun finish(ok: Boolean, text0: String) {
    Jobs.forget(id)
    val text = if (cancelled) "تم الإلغاء / Cancelled" else text0
    try { nm.cancel(id) } catch (e: Exception) {}
    try {
      nm.notify(id, Notification.Builder(appCtx, CH_DONE).setSmallIcon(R.drawable.logo).setContentTitle(title)
        .setContentText(text.take(120)).setAutoCancel(true).setContentIntent(pi()).build())
    } catch (e: Exception) {}
    Jobs.release()
  }
}
