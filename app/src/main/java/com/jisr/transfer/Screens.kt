package com.jisr.transfer

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun JisrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Color(0xFF0F766E), onPrimary = Color.White),
        content = content,
    )
}

@Composable
fun JisrApp(
    ui: Ui,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onPickAgain: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    val active = ui !is Ui.Home
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            when (ui) {
                Ui.Home -> Home(onSend, onReceive)
                is Ui.Pick -> PickScreen(ui, onPickAgain, onStart, onCancel)
                is Ui.Waiting -> Centered {
                    Image(ui.qr.asImageBitmap(), contentDescription = "رمز QR", modifier = Modifier.size(300.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("امسح الرمز من جهاز المستقبِل:\nجسر ← استقبال", textAlign = TextAlign.Center)
                    TextButton(onCancel) { Text("إلغاء") }
                }
                is Ui.Busy -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(ui.msg, textAlign = TextAlign.Center)
                    TextButton(onCancel) { Text("إلغاء") }
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
                    Text("الملف ${p.fileIndex + 1} من ${p.fileCount}: ${p.fileName}", maxLines = 1)
                    TextButton(onCancel) { Text("إلغاء") }
                }
                is Ui.Done -> Centered {
                    Text("اكتمل ✓", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(ui.text, textAlign = TextAlign.Center)
                    ui.where?.let { Text("المكان: $it") }
                    Spacer(Modifier.height(16.dp))
                    Button(onCancel) { Text("تم") }
                }
                is Ui.Failed -> Centered {
                    Text("تعذّر إكمال العملية", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(ui.msg, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onCancel) { Text("رجوع") }
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

@Composable
private fun Home(onSend: () -> Unit, onReceive: () -> Unit) = Centered {
    Text("جسر", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Text("نقل ملفات آمن وسريع بين أجهزة Android بلا إنترنت", textAlign = TextAlign.Center)
    Spacer(Modifier.height(40.dp))
    Button(onSend, Modifier.fillMaxWidth().height(60.dp)) { Text("إرسال ملفات") }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onReceive, Modifier.fillMaxWidth().height(60.dp)) { Text("استقبال ملفات") }
}

@Composable
private fun PickScreen(ui: Ui.Pick, onPickAgain: () -> Unit, onStart: () -> Unit, onCancel: () -> Unit) {
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
        Button(onStart, Modifier.fillMaxWidth()) { Text("ابدأ الإرسال") }
        OutlinedButton(onPickAgain, Modifier.fillMaxWidth()) { Text("تغيير الملفات") }
        TextButton(onCancel) { Text("رجوع") }
    }
}
