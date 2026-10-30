package com.jisr.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * خدمة مقدمة (dataSync): تُبقي العملية حيّة أثناء النقل حتى مع إغلاق الشاشة أو الخروج من التطبيق،
 * وتعرض إشعارًا بالتقدم والسرعة مع زر إلغاء، وإشعار نتيجة عند الانتهاء.
 */
class TransferService : Service() {
    companion object {
        private const val CH_ONGOING = "jisr_transfer"
        private const val CH_DONE = "jisr_done"
        private const val ACTION_CANCEL = "com.jisr.transfer.CANCEL"
        private const val ID = 1001
        private const val ID_DONE = 1002

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var lastNotifyNs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferHub.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        val n = buildOngoing(TransferHub.ui.value)
        try {
            ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            try {
                startForeground(ID, n)
            } catch (e2: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        collector?.cancel()
        collector = scope.launch { TransferHub.ui.collect { onState(it) } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        nm().createNotificationChannel(NotificationChannel(CH_ONGOING, "تقدم النقل", NotificationManager.IMPORTANCE_LOW))
        nm().createNotificationChannel(NotificationChannel(CH_DONE, "نتيجة النقل", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun onState(ui: Ui) {
        when (ui) {
            is Ui.Running -> {
                val now = System.nanoTime()
                if (now - lastNotifyNs >= 1_000_000_000L || ui.p.done >= ui.p.total) {
                    lastNotifyNs = now
                    nm().notify(ID, buildOngoing(ui))
                }
            }
            is Ui.Busy, is Ui.Waiting, is Ui.Confirm -> nm().notify(ID, buildOngoing(ui))
            is Ui.Done -> {
                nm().notify(ID_DONE, result("اكتمل ✓", ui.text.lineSequence().first()))
                finish()
            }
            is Ui.Failed -> {
                nm().notify(ID_DONE, result("تعذّر إكمال العملية", ui.msg))
                finish()
            }
            Ui.Home, is Ui.Pick -> finish()
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun result(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_jisr)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()

    private fun buildOngoing(ui: Ui): Notification {
        var title = "جسر"
        var text = "جارٍ التحضير…"
        var pct = -1
        when (ui) {
            is Ui.Busy -> text = ui.msg
            is Ui.Waiting -> text = "بانتظار المستقبِل — امسح الرمز (${ui.label})"
            is Ui.Confirm -> text = "بانتظار موافقتك على استلام ${ui.files.size} ملف"
            is Ui.Running -> {
                pct = if (ui.p.total > 0) ((ui.p.done * 100) / ui.p.total).toInt().coerceIn(0, 100) else 100
                title = if (ui.sending) "جارٍ الإرسال" else "جارٍ الاستقبال"
                text = "$pct% • ${fmtBytes(ui.bps)}/ث • ${etaText(ui.p, ui.bps)}"
            }
            else -> {}
        }
        val b = NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_jisr)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent())
            .addAction(0, "إلغاء", cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }
}
