package com.jisr.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: TransferViewModel by viewModels()
    private var afterPerms: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val go = afterPerms
        afterPerms = null
        if (r.values.all { it }) go?.invoke() else vm.fail("الصلاحية مطلوبة لإنشاء/الانضمام إلى الشبكة المحلية بين الجهازين")
    }
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.setFiles(uris)
    }
    private val scanLauncher = registerForActivityResult(ScanContract()) { res ->
        res.contents?.let { vm.onScanned(it) }
    }

    private fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withPerms(action: () -> Unit) {
        val missing = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            JisrTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    JisrApp(
                        ui = ui,
                        onSend = { pickLauncher.launch(arrayOf("*/*")) },
                        onReceive = { withPerms { scan() } },
                        onPickAgain = { pickLauncher.launch(arrayOf("*/*")) },
                        onStart = { withPerms { vm.startSend() } },
                        onCancel = vm::cancel,
                    )
                }
            }
        }
    }
}
