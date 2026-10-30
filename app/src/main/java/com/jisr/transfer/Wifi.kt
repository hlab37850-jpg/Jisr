package com.jisr.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.jisr.transfer.core.Crypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HotspotInfo(val ssid: String, val pass: String, val sec: Int)

interface WifiHost {
    suspend fun start(): HotspotInfo
    fun stop()
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** الوضع المتوافق: LocalOnlyHotspot (النطاق يختاره النظام). */
class HotspotHost(ctx: Context) : WifiHost {
    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    override suspend fun start(): HotspotInfo = suspendCancellableCoroutine { cont ->
        val cb = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation = r
                val info = try {
                    infoOf(r)
                } catch (e: Exception) {
                    runCatching { r.close() }
                    reservation = null
                    if (cont.isActive) cont.resumeWithException(IOException("تعذّر قراءة إعدادات الشبكة", e))
                    return
                }
                if (cont.isActive) cont.resume(info) else r.close()
            }

            override fun onFailed(reason: Int) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        IOException("تعذّر تشغيل نقطة الاتصال المحلية (رمز $reason). فعّل Wi‑Fi وأوقف الـ Hotspot العادي وتأكد من تفعيل الموقع."),
                    )
                }
            }
        }
        try {
            wm.startLocalOnlyHotspot(cb, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            cont.resumeWithException(IOException("الصلاحية ناقصة أو خدمة الموقع مغلقة", e))
        } catch (e: IllegalStateException) {
            cont.resumeWithException(IOException("نقطة الاتصال غير متاحة الآن", e))
        }
        cont.invokeOnCancellation { stop() }
    }

    @Suppress("DEPRECATION")
    private fun infoOf(r: WifiManager.LocalOnlyHotspotReservation): HotspotInfo {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            var ssid: String? = c.ssid
            if (ssid.isNullOrEmpty() && Build.VERSION.SDK_INT >= 33) ssid = c.wifiSsid?.toString()?.trim('"')
            if (ssid.isNullOrEmpty()) throw IOException("اسم الشبكة فارغ")
            return HotspotInfo(ssid, c.passphrase ?: "", c.securityType)
        }
        val c = r.wifiConfiguration ?: throw IOException("لا توجد إعدادات للشبكة")
        return HotspotInfo(c.SSID.trim('"'), c.preSharedKey.trim('"'), 1)
    }

    override fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }
}

/**
 * الوضع السريع: مجموعة Wi‑Fi Direct بنطاق 5GHz مُجبَر (GROUP_OWNER_BAND_5GHZ).
 * المستقبِل ينضم إليها كشبكة عادية (WifiNetworkSpecifier)، فلا يحتاج منطقًا مختلفًا.
 * تجريبي: يعتمد على دعم الجهاز لـ 5GHz في Wi‑Fi Direct.
 */
@SuppressLint("MissingPermission")
class P2pHost(ctx: Context) : WifiHost {
    private val app = ctx.applicationContext
    private val mgr: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    override suspend fun start(): HotspotInfo {
        val m = mgr ?: throw IOException("Wi‑Fi Direct غير مدعوم على هذا الجهاز")
        val ch = m.initialize(app, Looper.getMainLooper(), null) ?: throw IOException("تعذّر تهيئة Wi‑Fi Direct")
        channel = ch
        // إزالة أي مجموعة سابقة (النتيجة لا تهم)
        withTimeoutOrNull(2000) {
            suspendCancellableCoroutine<Unit> { c ->
                m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (c.isActive) c.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (c.isActive) c.resume(Unit)
                    }
                })
            }
        }
        val ssid = "DIRECT-jz-" + hex(Crypto.random(3))
        val pass = hex(Crypto.random(8))
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .enablePersistentMode(false)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            .build()
        suspendCancellableCoroutine<Unit> { c ->
            m.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (c.isActive) c.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (c.isActive) c.resumeWithException(IOException("تعذّر إنشاء شبكة 5GHz المباشرة (رمز $reason)"))
                }
            })
        }
        return HotspotInfo(ssid, pass, 1)
    }

    override fun stop() {
        val c = channel ?: return
        channel = null
        val m = mgr
        if (m == null) {
            runCatching { c.close() }
            return
        }
        runCatching {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    runCatching { c.close() }
                }

                override fun onFailure(reason: Int) {
                    runCatching { c.close() }
                }
            })
        }
    }
}

class WifiLink(
    val network: Network,
    private val cm: ConnectivityManager,
    private val cb: ConnectivityManager.NetworkCallback,
) : Closeable {
    override fun close() {
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}

/** المستقبِل ينضم إلى شبكة المرسِل (سيظهر حوار موافقة من النظام مرة واحدة). */
suspend fun joinWifi(ctx: Context, ssid: String, pass: String, sec: Int): WifiLink =
    suspendCancellableCoroutine { cont ->
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (sec == 3) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(b.build())
            .build()
        var delivered = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) {
                    delivered = true
                    cont.resume(WifiLink(network, cm, this))
                }
            }

            override fun onUnavailable() {
                if (cont.isActive) {
                    cont.resumeWithException(
                        IOException("تعذّر الاتصال بشبكة المرسِل (رُفض الطلب أو انتهت المهلة). إن تكرر الأمر: على المرسِل اضغط «جرّب الوضع المتوافق»."),
                    )
                }
            }
        }
        cm.requestNetwork(req, cb, 40_000)
        cont.invokeOnCancellation { if (!delivered) runCatching { cm.unregisterNetworkCallback(cb) } }
    }

/** عنوان المرسِل = بوابة الشبكة (يُنتظر حتى تتوفر خصائص الرابط). */
suspend fun gatewayOf(ctx: Context, net: Network): String? {
    val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
    repeat(25) {
        val lp = cm.getLinkProperties(net)
        if (lp != null) {
            lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress?.let { return it }
            val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.address
            if (a != null) return "${a[0].toInt() and 255}.${a[1].toInt() and 255}.${a[2].toInt() and 255}.1"
        }
        delay(200)
    }
    return null
}

/** قفل Wi‑Fi منخفض الكمون + منع نوم المعالج أثناء النقل. */
class PerfLocks(ctx: Context) {
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "jisr:wifi")
        .apply { setReferenceCounted(false); acquire() }
    private val wake = ctx.applicationContext.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jisr:xfer")
        .apply { acquire(2 * 60 * 60 * 1000L) }

    fun release() {
        runCatching { if (wifi.isHeld) wifi.release() }
        runCatching { if (wake.isHeld) wake.release() }
    }
}
