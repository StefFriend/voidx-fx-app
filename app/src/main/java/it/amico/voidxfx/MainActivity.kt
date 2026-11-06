package it.amico.voidxfx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * VoidX FX: la stessa pagina della scheda (assets/index.html) in una WebView; le richieste /api/... passano
 * dal Bluetooth LE invece che dal Wi-Fi.
 *
 * Protocollo (vedi firmware ble.c): servizio 9f1c0001-..., RX 9f1c0002 (scrittura), TX 9f1c0003 (notifiche).
 * Ogni pacchetto: 1 byte di flag (0x80 inizio messaggio, 0x40 fine) + dati.
 * Richiesta "<id> <percorso>?<query>\n" + corpo binario; risposta "<id> <stato>\n" + JSON.
 * Una richiesta alla volta: la successiva parte quando arriva la risposta (o scade l'attesa).
 */
class MainActivity : Activity() {
    companion object {
        val SVC: UUID = UUID.fromString("9f1c0001-5658-4658-9a3b-2c9d0a6e1f10")
        val RX: UUID = UUID.fromString("9f1c0002-5658-4658-9a3b-2c9d0a6e1f10")
        val TX: UUID = UUID.fromString("9f1c0003-5658-4658-9a3b-2c9d0a6e1f10")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQ_PERM = 1
        const val REQ_FILE = 2
        const val ST_OFF = 0
        const val ST_BUSY = 1
        const val ST_READY = 2
    }

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private val bt = HandlerThread("ble").apply { start() }
    private val h = Handler(bt.looper)               // tutto il Bluetooth su un solo thread

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var state = ST_OFF
    private var scanning = false
    private var wantConnected = true

    private class Req(val id: Int, val data: ByteArray, val timeoutMs: Long)
    private val queue = ArrayDeque<Req>()
    private var cur: Req? = null
    private var chunks: List<ByteArray> = emptyList()
    private var chunkIdx = 0
    private val resp = ByteArrayOutputStream()
    private var fileCb: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // dal vivo lo schermo resta acceso
        window.statusBarColor = 0xFF0E1116.toInt()
        web = WebView(this)
        web.setBackgroundColor(0xFF0E1116.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                return try { startActivityForResult(Intent.createChooser(i, "Scegli il file"), REQ_FILE); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "VXBridge")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @Deprecated("API di sistema")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val u = if (resultCode == RESULT_OK) data?.data else null
            fileCb?.onReceiveValue(if (u != null) arrayOf(u) else null)
            fileCb = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        wantConnected = false
        h.post { stopScan(); closeGatt() }
        bt.quitSafely()
        super.onDestroy()
    }

    /* ---------------- ponte con la pagina ---------------- */
    inner class Bridge {
        @JavascriptInterface fun ready() { ui.post { ensurePermsAndConnect() } }
        @JavascriptInterface fun connect() { ui.post { wantConnected = true; ensurePermsAndConnect() } }
        @JavascriptInterface fun request(id: Int, url: String, b64: String) {
            val path = url.replaceFirst(Regex("^https?://[^/]+"), "")
            val body = if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64, Base64.DEFAULT)
            val head = "$id $path\n".toByteArray(Charsets.UTF_8)
            val data = ByteArray(head.size + body.size)
            System.arraycopy(head, 0, data, 0, head.size)
            System.arraycopy(body, 0, data, head.size, body.size)
            h.post {
                if (state != ST_READY) { fail(id, "non collegato"); return@post }
                queue.add(Req(id, data, if (body.isNotEmpty()) 90000L else 8000L))
                pump()
            }
        }
    }

    private fun js(code: String) = ui.post { web.evaluateJavascript(code, null) }
    private fun status(st: Int, txt: String) { state = st; js("window.vxStatus && window.vxStatus($st, ${JSONObject.quote(txt)})") }
    private fun fail(id: Int, why: String) = js("window.vxFail && window.vxFail($id, ${JSONObject.quote(why)})")

    /* ---------------- permessi ---------------- */
    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsAndConnect() {
        val missing = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), REQ_PERM); return }
        if (adapter?.isEnabled != true) {
            status(ST_OFF, "Bluetooth spento")
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: SecurityException) { }
            return
        }
        h.post { startScan() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) ensurePermsAndConnect()
            else status(ST_OFF, "permessi Bluetooth negati")
        }
    }

    /* ---------------- ricerca e collegamento ---------------- */
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post {
                if (!scanning) return@post
                stopScan()
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) { h.post { scanning = false; status(ST_OFF, "ricerca fallita ($errorCode)") } }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (gatt != null || scanning) return
        val sc = adapter?.bluetoothLeScanner ?: run { status(ST_OFF, "Bluetooth non disponibile"); return }
        scanning = true
        status(ST_BUSY, "ricerca VoidX-FX…")
        val f = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { sc.startScan(listOf(f), s, scanCb) } catch (e: SecurityException) { scanning = false; status(ST_OFF, "permessi Bluetooth negati"); return }
        h.postDelayed({
            if (scanning) { stopScan(); status(ST_OFF, "VoidX-FX non trovato (connessione Bluetooth attiva sulla scheda?)") }
        }, 12000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        status(ST_BUSY, "collegamento…")
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null
        rx = null
    }

    private fun dropAll(why: String) {
        cur?.let { fail(it.id, why) }
        cur = null
        while (queue.isNotEmpty()) fail(queue.poll()!!.id, why)
        h.removeCallbacksAndMessages(TIMEOUT_TOKEN)
    }

    private val TIMEOUT_TOKEN = Any()

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!g.requestMtu(247)) g.discoverServices()
                } else {
                    closeGatt()
                    dropAll("collegamento perso")
                    status(ST_OFF, "scollegato")
                    if (wantConnected) h.postDelayed({ startScan() }, 2000)
                }
            }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            h.post { if (st == BluetoothGatt.GATT_SUCCESS) mtu = m; g.discoverServices() }
        }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            h.post {
                val s = g.getService(SVC)
                val r = s?.getCharacteristic(RX)
                val t = s?.getCharacteristic(TX)
                if (r == null || t == null) { status(ST_OFF, "servizio VoidX assente"); closeGatt(); return@post }
                rx = r
                g.setCharacteristicNotification(t, true)
                val d = t.getDescriptor(CCCD)
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            h.post {
                if (st == BluetoothGatt.GATT_SUCCESS) { status(ST_READY, "Bluetooth: " + (g.device.name ?: "VoidX-FX")); pump() }
                else status(ST_OFF, "notifiche non attivate ($st)")
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            h.post {
                if (st != BluetoothGatt.GATT_SUCCESS) { cur?.let { fail(it.id, "scrittura fallita") }; cur = null; pump(); return@post }
                chunkIdx++
                writeNext()
            }
        }
        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            val copy = v.copyOf()
            h.post { onPacket(copy) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            h.post { onPacket(copy) }
        }
    }

    /* ---------------- richieste ---------------- */
    private fun pump() {
        if (cur != null || state != ST_READY || queue.isEmpty()) return
        val r = queue.poll() ?: return
        cur = r
        val size = (mtu - 3 - 1).coerceAtLeast(20)
        val list = ArrayList<ByteArray>()
        var o = 0
        do {
            val k = minOf(size, r.data.size - o)
            val p = ByteArray(k + 1)
            p[0] = ((if (o == 0) 0x80 else 0) or (if (o + k >= r.data.size) 0x40 else 0)).toByte()
            System.arraycopy(r.data, o, p, 1, k)
            list.add(p)
            o += k
        } while (o < r.data.size)
        chunks = list
        chunkIdx = 0
        resp.reset()
        h.postAtTime({ if (cur === r) { fail(r.id, "nessuna risposta"); cur = null; pump() } }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + r.timeoutMs)
        writeNext()
    }

    @SuppressLint("MissingPermission")
    private fun writeNext() {
        val g = gatt ?: return
        val c = rx ?: return
        if (cur == null || chunkIdx >= chunks.size) return
        val p = chunks[chunkIdx]
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = p
                g.writeCharacteristic(c)
            }
        }
        if (!ok) h.postDelayed({ writeNext() }, 15)          // coda del sistema piena: si riprova
    }

    private fun onPacket(p: ByteArray) {
        if (p.isEmpty()) return
        val f = p[0].toInt() and 0xFF
        if (f and 0x80 != 0) resp.reset()
        resp.write(p, 1, p.size - 1)
        if (f and 0x40 == 0) return
        val all = resp.toByteArray()
        resp.reset()
        val nl = all.indexOf('\n'.code.toByte())
        val head = String(all, 0, if (nl < 0) all.size else nl, Charsets.UTF_8)
        val body = if (nl < 0) "" else String(all, nl + 1, all.size - nl - 1, Charsets.UTF_8)
        val parts = head.trim().split(" ")
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return
        val code = parts.getOrNull(1)?.toIntOrNull() ?: 500
        js("window.vxResp && window.vxResp($id, $code, ${JSONObject.quote(body)})")
        if (cur?.id == id) {
            cur = null
            h.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            pump()
        }
    }
}
