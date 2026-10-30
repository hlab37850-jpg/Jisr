package com.jisr.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private var afterPerms: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val go = afterPerms
        afterPerms = null
        if (hasCorePerm()) go?.invoke() else TransferHub.fail("الصلاحية مطلوبة لإنشاء/الانضمام إلى الشبكة المحلية بين الجهازين")
    }
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) TransferHub.setFiles(uris)
    }
    private val scanLauncher = registerForActivityResult(ScanContract()) { res ->
        val text = res.contents ?: return@registerForActivityResult
        TransferHub.startReceive(text)
        TransferService.start(this)
    }

    private fun corePerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasCorePerm() = checkSelfPermission(corePerm()) == PackageManager.PERMISSION_GRANTED

    /** الصلاحية الأساسية إلزامية؛ صلاحية الإشعارات اختيارية (الخدمة تعمل بدونها). */
    private fun withPerms(action: () -> Unit) {
        val want = buildList {
            add(corePerm())
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            afterPerms = action
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun scan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("وجّه الكاميرا نحو رمز QR على جهاز المرسِل")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /** استقبال ملفات مشاركة من تطبيقات أخرى (مشاركة ← جسر). */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> return
        }
        i.action = null
        val s = TransferHub.ui.value
        if (uris.isNotEmpty() && (s is Ui.Home || s is Ui.Pick)) TransferHub.setFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        val actions = Actions(
            send = { pickLauncher.launch(arrayOf("*/*")) },
            receive = { withPerms { scan() } },
            pickAgain = { pickLauncher.launch(arrayOf("*/*")) },
            start = {
                withPerms {
                    TransferHub.startSend()
                    TransferService.start(this)
                }
            },
            cancel = TransferHub::cancel,
            accept = { TransferHub.accept(true) },
            decline = { TransferHub.accept(false) },
            setFast = TransferHub::setFast,
            switchMode = TransferHub::switchMode,
            clearCrash = TransferHub::clearCrash,
        )
        setContent {
            val ui by TransferHub.ui.collectAsStateWithLifecycle()
            val history by TransferHub.history.collectAsStateWithLifecycle()
            val crash by TransferHub.crash.collectAsStateWithLifecycle()
            val fast by TransferHub.fast.collectAsStateWithLifecycle()
            JisrTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    JisrApp(ui, history, crash, fast, actions)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }
}
