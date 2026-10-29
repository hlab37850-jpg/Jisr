package com.jisr.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HotspotInfo(val ssid: String, val pass: String, val sec: Int)

/** المرسِل ينشئ نقطة اتصال محلية (LocalOnlyHotspot): بلا إنترنت وبلا SIM، وعلى 5GHz غالبًا. */
class HotspotHost(ctx: Context) {
    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    suspend fun start(): HotspotInfo = suspendCancellableCoroutine { cont ->
        val cb = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation = r
                if (cont.isActive) cont.resume(infoOf(r)) else r.close()
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
            return HotspotInfo(c.ssid ?: "", c.passphrase ?: "", c.securityType)
        }
        val c = r.wifiConfiguration ?: throw IOException("لا توجد إعدادات للشبكة")
        return HotspotInfo(c.SSID.trim('"'), c.preSharedKey.trim('"'), 1)
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
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
                if (cont.isActive) cont.resumeWithException(IOException("تعذّر الاتصال بشبكة المرسِل (رُفض الطلب أو انتهت المهلة)"))
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
