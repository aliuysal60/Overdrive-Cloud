package com.overdrive.app.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BYD DiLink 51.x (SA8155, Android 11) araçlarına özel ADB ön-hazırlığı.
 *
 * Bu araçlarda dadb'nin (v1.2.8, TLS'siz klasik ADB istemcisi) loopback
 * 127.0.0.1:5555'e bağlanabilmesi için iki koşul gerekiyor:
 *
 *  1) Kablosuz ADB'nin AÇIK olması — TS Settings servisi
 *     (com.ts.appservice.settings/.SettingsService, exported, izinsiz) üzerinden
 *     property binder'ına yazılır: setSystemPersist("persist.adb.tcp.port","5555")
 *     + anlık tetik "sys.connect.adb.wiress=1" (init.bydidc.rc adbd'yi yeniden
 *     başlatır). Yedek yol: OTA test komutu y(31,77) → adb_enabled=1.
 *
 *  2) adbd'nin KLASİK modda olması — adbd TLS moduna dönerse klasik CNXN el
 *     sıkışması hiç tamamlanmaz (ekranda RSA diyalogu bile çıkmaz; sessiz
 *     başarısızlık). TLS kapatma döngüsü: OTA y(31,88) (adb_wifi=0) →
 *     wiress=0 → bekle → wiress=1 (adbd klasik modda yeniden doğar).
 *
 * Tüm adımlar uygulamanın kendi external files dizinindeki adb_fix.log'a
 * yazılır: /sdcard/Android/data/com.overdrive.app/files/adb_fix.log
 * (kabuktan `cat` ile tek turda teşhis).
 */
object BydAdbEnabler {
    private const val TAG = "BydAdbEnabler"

    // TS Settings servisi (property köprüsü)
    private const val TS_PKG = "com.ts.appservice.settings"
    private const val TS_SERVICE = "com.ts.appservice.settings.SettingsService"
    private const val ADAPTER_DESC = "com.ts.lib.settings.ISettingsAdapterService"
    private const val PROPERTY_DESC = "com.ts.lib.settings.property.IPropertySettingsService"

    // OTA test komutu servisi
    private const val OTA_DESC = "com.byd.sdkupdate.inner.IOtaTestCommand"
    private const val OTA_PKG = "com.byd.ota"
    private const val OTA_SERVICE = "com.byd.ota.view.OtaUiService"
    private const val OTA_ACTION = "com.intent.action.OTA_UPGRADE_TEST"

    private const val ADB_PORT = 5555

    private val enableInFlight = AtomicBoolean(false)
    private val lastEnableMs = AtomicLong(0)

    fun log(context: Context, line: String) {
        try {
            android.util.Log.i(TAG, line)
            val dir = context.getExternalFilesDir(null) ?: return
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            FileOutputStream(File(dir, "adb_fix.log"), true).use {
                it.write("[$stamp] $line\n".toByteArray(Charsets.UTF_8))
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun isPortOpen(): Boolean = try {
        Socket("127.0.0.1", ADB_PORT).use { true }
    } catch (e: Exception) {
        false
    }

    /** Servise bağlan, job'ı çalıştır, kop. Herhangi bir hata loglanır ve null döner. */
    private fun <T> withBoundService(
        context: Context,
        intent: Intent,
        timeoutMs: Long,
        what: String,
        job: (IBinder) -> T
    ): T? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<IBinder>(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                holder[0] = binder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        return try {
            val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                log(context, "$what: bind reddedildi (servis yok?)")
                null
            } else {
                try {
                    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                        log(context, "$what: bağlantı zaman aşımı")
                        null
                    } else {
                        val b = holder[0]
                        if (b == null) { log(context, "$what: binder null"); null } else job(b)
                    }
                } finally {
                    try { context.unbindService(conn) } catch (ignored: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            log(context, "$what hata: $t")
            null
        }
    }

    /** IPropertySettingsService.setSystemPersist(key, value) — transact 10. */
    private fun tsSetProp(context: Context, binder: IBinder, key: String, value: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PROPERTY_DESC)
            data.writeString(key)
            data.writeString(value)
            if (!binder.transact(10, data, reply, 0)) "transact10=reddedildi"
            else {
                runCatching { reply.readException() }
                    .fold({ "ok" }, { "exception:${it.message}" })
            }
        } catch (t: Throwable) {
            "hata:$t"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** TS Settings → getSettingsAdapterService("property") → property binder → setProp. */
    private fun tsWrite(context: Context, key: String, value: String): String? {
        val intent = Intent().setComponent(ComponentName(TS_PKG, TS_SERVICE))
        return withBoundService(context, intent, 10_000, "ts:$key") { adapter ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ADAPTER_DESC)
                data.writeString("property")
                if (!adapter.transact(2, data, reply, 0)) {
                    log(context, "ts: getSettingsAdapterService reddedildi")
                    return@withBoundService null
                }
                runCatching { reply.readException() }.onFailure {
                    log(context, "ts: adapter exception $it")
                    return@withBoundService null
                }
                val prop = reply.readStrongBinder()
                if (prop == null) {
                    log(context, "ts: property binder null")
                    null
                } else tsSetProp(context, prop, key, value)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    /** OTA test komutu y(cmd, val) — transact 3, (int,int) parametre. */
    private fun otaY(context: Context, cmd: Int, value: Int): String? {
        val intent = Intent(OTA_ACTION).setComponent(ComponentName(OTA_PKG, OTA_SERVICE))
        return withBoundService(context, intent, 10_000, "ota:y($cmd,$value)") { svc ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(OTA_DESC)
                data.writeInt(cmd)
                data.writeInt(value)
                if (!svc.transact(3, data, reply, 0)) "transact=reddedildi"
                else {
                    runCatching { reply.readException() }
                        .fold({ "ok" }, { "exception:${it.message}" })
                }
            } catch (t: Throwable) {
                "hata:$t"
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    /**
     * Tam etkinleştirme: port kapalıysa çağrılır. TS köprüsü ile property'leri
     * yaz + OTA y(31,77)/(31,99) yedeği; port açılana dek bekle (en çok ~25 sn).
     */
    fun ensureAdbEnabled(context: Context) {
        if (!enableInFlight.compareAndSet(false, true)) return
        try {
            val since = System.currentTimeMillis() - lastEnableMs.get()
            if (since < 60_000) {
                log(context, "enable: son denemeden ${since}ms önce — atlanıyor")
                return
            }
            lastEnableMs.set(System.currentTimeMillis())
            log(context, "=== ensureAdbEnabled başlıyor (port kapalı) ===")

            // 1) TS Settings köprüsü — anlık tetik + kalıcı port
            log(context, "ts persist.adb.tcp.port=5555 → ${tsWrite(context, "persist.adb.tcp.port", "5555")}")
            log(context, "ts service.adb.tcp.port=5555 → ${tsWrite(context, "service.adb.tcp.port", "5555")}")
            log(context, "ts persist.sys.adb.wiress.enable=true → ${tsWrite(context, "persist.sys.adb.wiress.enable", "true")}")
            log(context, "ts sys.connect.adb.wiress=1 → ${tsWrite(context, "sys.connect.adb.wiress", "1")}")

            // 2) OTA yedeği: adb_enabled + wifi
            log(context, "ota y(31,77) adb_enabled → ${otaY(context, 31, 77)}")
            log(context, "ota y(31,99) adb_wifi → ${otaY(context, 31, 99)}")

            // 3) adbd'nin yeniden doğmasını bekle
            for (i in 1..25) {
                Thread.sleep(1000)
                if (isPortOpen()) {
                    log(context, "port $ADB_PORT AÇILDI (${i}. sn) ✓")
                    return
                }
            }
            log(context, "port $ADB_PORT hâlâ kapalı ✗")
        } finally {
            enableInFlight.set(false)
        }
    }

    /**
     * TLS kapama / klasik moda alma döngüsü: bağlantı denemesi başarısızsa
     * (adbd TLS modunda olabilir) bir kezliğine çağrılır.
     */
    fun runClassicModeCycle(context: Context) {
        log(context, "=== klasik mod döngüsü başlıyor ===")
        // adb_wifi=0 (TLS wifi debug kapat) — OTA y(31,88); 88 kapat, 66 tamamen sıfırla
        log(context, "ota y(31,88) adb_wifi=0 → ${otaY(context, 31, 88)}")
        Thread.sleep(500)
        // wiress 0 → 1: adbd klasik modda yeniden doğar
        log(context, "ts wiress=0 → ${tsWrite(context, "sys.connect.adb.wiress", "0")}")
        Thread.sleep(1000)
        log(context, "ts wiress=1 → ${tsWrite(context, "sys.connect.adb.wiress", "1")}")
        // adbd doğarken bekle
        for (i in 1..10) {
            Thread.sleep(500)
            if (isPortOpen()) {
                log(context, "port açık (${i * 500}ms) — klasik mod döngüsü tamam ✓")
                return
            }
        }
        log(context, "klasik mod döngüsü sonrası port hâlâ kapalı ✗")
    }
}
