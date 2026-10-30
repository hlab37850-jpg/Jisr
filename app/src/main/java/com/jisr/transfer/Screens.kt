package com.jisr.transfer

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Actions(
    val send: () -> Unit,
    val receive: () -> Unit,
    val pickAgain: () -> Unit,
    val start: () -> Unit,
    val cancel: () -> Unit,
    val accept: () -> Unit,
    val decline: () -> Unit,
    val setFast: (Boolean) -> Unit,
    val switchMode: () -> Unit,
    val clearCrash: () -> Unit,
)

@Composable
fun JisrTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF4FD1C5), onPrimary = Color(0xFF00201D))
    } else {
        lightColorScheme(primary = Color(0xFF0F766E), onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun JisrApp(ui: Ui, history: List<HistoryItem>, crash: String?, fast: Boolean, a: Actions) {
    val view = LocalView.current
    val active = ui !is Ui.Home
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            when (ui) {
                Ui.Home -> Home(a, history, crash)
                is Ui.Pick -> PickScreen(ui, fast, a)
                is Ui.Waiting -> Centered {
                    Image(ui.qr.asImageBitmap(), contentDescription = "رمز QR", modifier = Modifier.size(300.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("امسح الرمز من جهاز المستقبِل:\nجسر ← استقبال", textAlign = TextAlign.Center)
                    Text(ui.label, color = MaterialTheme.colorScheme.primary)
                    TextButton(a.switchMode) {
                        Text(if (fast) "الاتصال لا ينجح؟ جرّب الوضع المتوافق" else "جرّب الوضع السريع 5GHz")
                    }
                    TextButton(a.cancel) { Text("إلغاء") }
                }
                is Ui.Busy -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(ui.msg, textAlign = TextAlign.Center)
                    TextButton(a.cancel) { Text("إلغاء") }
                }
                is Ui.Confirm -> Centered {
                    Text("جهاز يريد إرسال ${ui.files.size} ملف", style = MaterialTheme.typography.titleLarge)
                    Text(fmtBytes(ui.total))
                    Spacer(Modifier.height(8.dp))
                    ui.files.take(6).forEach { Text(it.name, maxLines = 1) }
                    if (ui.files.size > 6) Text("… و${ui.files.size - 6} ملف آخر")
                    Spacer(Modifier.height(20.dp))
                    Button(a.accept, Modifier.fillMaxWidth()) { Text("قبول") }
                    OutlinedButton(a.decline, Modifier.fillMaxWidth()) { Text("رفض") }
                }
                is Ui.Running -> Centered {
                    val p = ui.p
                    Text(if (ui.sending) "جارٍ الإرسال" else "جارٍ الاستقبال", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 1f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${fmtBytes(p.done)} / ${fmtBytes(p.total)}")
                    Text("السرعة: ${fmtBytes(ui.bps)}/ث", style = MaterialTheme.typography.titleMedium)
                    Text(etaText(p, ui.bps))
                    Text("الملف ${p.fileIndex + 1} من ${p.fileCount}: ${p.fileName}", maxLines = 1)
                    Text("يمكنك الخروج من التطبيق؛ النقل يستمر في الخلفية", style = MaterialTheme.typography.bodySmall)
                    TextButton(a.cancel) { Text("إلغاء") }
                }
                is Ui.Done -> Centered {
                    val ctx = LocalContext.current
                    Text("اكتمل ✓", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(ui.text, textAlign = TextAlign.Center)
                    ui.where?.let { Text("المكان: $it") }
                    Spacer(Modifier.height(16.dp))
                    if (!ui.sending) {
                        OutlinedButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("فتح التنزيلات") }
                    }
                    Button(a.cancel) { Text("تم") }
                }
                is Ui.Failed -> Centered {
                    Text("تعذّر إكمال العملية", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(ui.msg, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(a.cancel) { Text("رجوع") }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

private fun historyLine(h: HistoryItem): String {
    val d = SimpleDateFormat("d/M HH:mm", Locale.US).format(Date(h.time))
    return "${if (h.sending) "↑ إرسال" else "↓ استقبال"} ${h.count} ملف • ${fmtBytes(h.bytes)} • ${fmtBytes(h.avgBps)}/ث • $d"
}

@Composable
private fun Home(a: Actions, history: List<HistoryItem>, crash: String?) {
    val clip = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("جسر", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("نقل ملفات آمن وسريع بين أجهزة Android بلا إنترنت", textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(a.send, Modifier.fillMaxWidth().height(60.dp)) { Text("إرسال ملفات") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(a.receive, Modifier.fillMaxWidth().height(60.dp)) { Text("استقبال ملفات") }
        Spacer(Modifier.height(8.dp))
        Text("تلميح: شارك أي ملف من المعرض أو الملفات إلى «جسر» مباشرة", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (crash != null) {
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("حدث عطل في المرة السابقة", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Text(crash.take(500), style = MaterialTheme.typography.bodySmall, maxLines = 10)
                    Row {
                        TextButton({ clip.setText(AnnotatedString(crash)) }) { Text("نسخ التقرير") }
                        TextButton(a.clearCrash) { Text("حذف") }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("آخر العمليات", style = MaterialTheme.typography.titleMedium)
            history.take(5).forEach { Text(historyLine(it), modifier = Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PickScreen(ui: Ui.Pick, fast: Boolean, a: Actions) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "الملفات المحددة (${ui.files.size}) — ${fmtBytes(ui.files.sumOf { it.size })}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (ui.skipped > 0) {
            Text("تم تجاهل ${ui.skipped} ملف لأن حجمه غير معروف", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) {
            items(ui.files) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(f.name, Modifier.weight(1f), maxLines = 1)
                    Text(fmtBytes(f.size))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fast, onCheckedChange = a.setFast)
            Spacer(Modifier.size(12.dp))
            Column {
                Text("الوضع السريع 5GHz (تجريبي)")
                Text("عطّله إن تعذّر اتصال المستقبِل", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(a.start, Modifier.fillMaxWidth()) { Text("ابدأ الإرسال") }
        OutlinedButton(a.pickAgain, Modifier.fillMaxWidth()) { Text("تغيير الملفات") }
        TextButton(a.cancel) { Text("رجوع") }
    }
}
