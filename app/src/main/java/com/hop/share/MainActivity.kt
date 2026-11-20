package com.hop.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Hop: phone-to-phone file sharing. Google's Nearby Connections finds phones over
 * Bluetooth and moves the files over Wi-Fi Direct / hotspot for speed.
 */
class MainActivity : Activity() {
    data class Peer(val id: String, val name: String)

    private val svc = "com.hop.share"
    private lateinit var client: ConnectionsClient
    private lateinit var myName: String
    private lateinit var radar: RadarView
    private lateinit var status: TextView
    private lateinit var picked: TextView
    private lateinit var speedTv: TextView
    private lateinit var bar: ProgressBar
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var main: LinearLayout
    private lateinit var setup: LinearLayout
    private lateinit var contBtn: Button
    private var started = false
    private var askedPerm = false
    private val rows = ArrayList<Row>()

    private val peers = LinkedHashMap<String, Peer>()
    private var files: List<Uri> = emptyList()
    private var outgoing: String? = null
    private var sentCount = 0
    private var t0 = 0L
    private val names = HashMap<Long, String>()
    private val incoming = HashMap<Long, Uri>()
    private val track = HashSet<Long>()
    private val done = HashSet<Long>()
    private val prog = HashMap<Long, Pair<Long, Long>>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Native apps can read the phone's real name.
        myName = Settings.Global.getString(contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        client = Nearby.getConnectionsClient(this)
        buildUi()
    }

    override fun onDestroy() {
        client.stopAllEndpoints()
        super.onDestroy()
    }

    // ---------- permissions ----------
    private fun needed(): List<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_ADVERTISE
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
        }
        l += Manifest.permission.ACCESS_COARSE_LOCATION
        l += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.NEARBY_WIFI_DEVICES
        return l
    }

    private fun missing(): Array<String> =
        needed().filter {
            it != Manifest.permission.ACCESS_FINE_LOCATION &&
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refreshSetup()
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val ink = Color.parseColor("#10302B")
        val muted = Color.parseColor("#5F7A74")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E9F1EE"))
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Hop"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
        })
        root.addView(TextView(this).apply { text = "Visible as $myName"; setTextColor(muted) })

        radar = RadarView(this)
        radar.onTap = { send(it) }
        root.addView(radar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply { text = "Looking for nearby phones…"; setTextColor(ink); textSize = 16f }
        picked = TextView(this).apply { text = "No files chosen yet."; setTextColor(muted) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        speedTv = TextView(this).apply { setTextColor(muted) }
        val choose = Button(this).apply {
            text = "Choose files to send"; isAllCaps = false; setTextColor(Color.WHITE)
            setBackgroundColor(ink); setOnClickListener { pick() }
        }
        root.addView(status); root.addView(picked); root.addView(bar); root.addView(speedTv)
        root.addView(choose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        main = root
        main.visibility = View.GONE
        setContentView(FrameLayout(this).apply { addView(main); addView(buildSetup()) })
    }

    // ---------- setup screen ----------
    private class Row(val label: String, val ok: () -> Boolean, val btn: String, val fix: () -> Unit) {
        lateinit var tv: TextView
        lateinit var b: Button
    }

    private fun isWifiOn() =
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    private fun isBtOn() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    private fun isLocOn() = getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private fun askPermissions() {
        if (!askedPerm) {
            askedPerm = true
            requestPermissions(needed().toTypedArray(), 1)
        } else {
            // Already asked once: Android may not show the prompt again, so open the app's settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun buildSetup(): LinearLayout {
        val ink = Color.parseColor("#10302B")
        val muted = Color.parseColor("#5F7A74")
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E9F1EE"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        setup.addView(TextView(this).apply {
            text = "Get Hop ready"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
        })
        setup.addView(TextView(this).apply {
            text = "Turn these on so Hop can find nearby phones and send files fast. When you come back from Settings, this list updates by itself."
            setTextColor(muted); setPadding(0, dp(6), 0, dp(20))
        })
        rows += Row("Wi\u2011Fi is on", { isWifiOn() }, "Turn on") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        rows += Row("Bluetooth is on", { isBtOn() }, "Turn on") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        rows += Row("Location is on", { isLocOn() }, "Turn on") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        rows += Row("Permissions allowed", { missing().isEmpty() }, "Allow") { askPermissions() }
        for (r in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            r.tv = TextView(this).apply { textSize = 17f; setTextColor(ink) }
            r.b = Button(this).apply { text = r.btn; isAllCaps = false; setOnClickListener { r.fix() } }
            line.addView(r.tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(r.b)
            setup.addView(line)
        }
        setup.addView(TextView(this).apply {
            text = "If a permission prompt says Nearby devices or Location, choose Allow (Precise if offered)."
            setTextColor(muted); setPadding(0, dp(12), 0, dp(12))
        })
        contBtn = Button(this).apply {
            text = "Continue"; isAllCaps = false; setTextColor(Color.WHITE)
            setBackgroundColor(ink)
            setOnClickListener {
                setup.visibility = View.GONE
                main.visibility = View.VISIBLE
                if (!started) { started = true; startNearby() }
            }
        }
        setup.addView(contBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        return setup
    }

    private fun refreshSetup() {
        if (!::setup.isInitialized) return
        var all = true
        for (r in rows) {
            val ok = r.ok()
            all = all && ok
            r.tv.text = (if (ok) "\u2705  " else "\u2B1C  ") + r.label
            r.b.visibility = if (ok) View.GONE else View.VISIBLE
        }
        contBtn.isEnabled = all
        contBtn.alpha = if (all) 1f else 0.4f
        // If something gets switched off later, bring the setup list back.
        if (!all && started) { setup.visibility = View.VISIBLE; main.visibility = View.GONE }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    private fun refresh() {
        radar.peers = peers.values.toList()
        if (outgoing == null && done.isEmpty()) {
            val n = peers.size
            status.text = if (n == 0) "Looking for nearby phones… Open Hop on the other phone."
            else "$n phone${if (n > 1) "s" else ""} nearby. Tap one to send."
        }
    }

    private fun fmt(b: Double): String = when {
        b > 1e9 -> "%.2f GB".format(b / 1e9)
        b > 1e6 -> "%.1f MB".format(b / 1e6)
        b > 1e3 -> "%d KB".format((b / 1e3).toInt())
        else -> "%d B".format(b.toInt())
    }

    // ---------- choosing files ----------
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 2 && res == RESULT_OK && data != null) {
            val c = data.clipData
            files = if (c != null) (0 until c.itemCount).map { c.getItemAt(it).uri } else listOfNotNull(data.data)
            picked.text = "${files.size} file${if (files.size > 1) "s" else ""} ready. Tap a phone to send."
        }
    }

    private fun queryName(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "file"

    // ---------- Nearby Connections ----------
    private fun startNearby() {
        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startAdvertising(myName, svc, connCb, adv)
            .addOnFailureListener { status.text = "Others can't see you: ${it.message}" }
        val dis = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startDiscovery(svc, discCb, dis)
            .addOnFailureListener { status.text = "Can't look for phones: ${it.message}" }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            peers[id] = Peer(id, info.endpointName); refresh()
        }
        override fun onEndpointLost(id: String) { peers.remove(id); refresh() }
    }

    private fun send(p: Peer) {
        if (files.isEmpty()) { Toast.makeText(this, "Choose files to send first", Toast.LENGTH_SHORT).show(); return }
        track.clear(); done.clear(); prog.clear(); names.clear(); incoming.clear()
        t0 = 0L; bar.progress = 0; speedTv.text = ""
        outgoing = p.id
        status.text = "Connecting to ${p.name}…"
        client.requestConnection(myName, p.id, connCb).addOnFailureListener {
            status.text = "Couldn't connect: ${it.message}"; outgoing = null
        }
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (id == outgoing) {
                client.acceptConnection(id, payloadCb)
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("${info.endpointName} wants to send you files")
                    .setMessage("Both phones should show the code ${info.authenticationToken}.")
                    .setCancelable(false)
                    .setPositiveButton("Accept") { _, _ -> client.acceptConnection(id, payloadCb) }
                    .setNegativeButton("Decline") { _, _ -> client.rejectConnection(id) }
                    .show()
            }
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (!r.status.isSuccess) { status.text = "Connection failed or declined."; outgoing = null; return }
            if (id == outgoing) sendFiles(id) else status.text = "Connected. Receiving…"
        }
        override fun onDisconnected(id: String) {
            if (id == outgoing) outgoing = null
            ui.post { if (done.isEmpty()) refresh() }
        }
    }

    private fun sendFiles(id: String) {
        sentCount = 0
        for (u in files) {
            val pfd = contentResolver.openFileDescriptor(u, "r") ?: continue
            val p = Payload.fromFile(pfd)
            track += p.id
            sentCount++
            // The receiver learns the real file name from this small message.
            client.sendPayload(id, Payload.fromBytes("${p.id}|${queryName(u)}".toByteArray()))
            client.sendPayload(id, p)
        }
        status.text = "Sending $sentCount file${if (sentCount > 1) "s" else ""}…"
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            if (p.type == Payload.Type.BYTES) {
                val s = String(p.asBytes() ?: return)
                val i = s.indexOf('|')
                if (i > 0) s.substring(0, i).toLongOrNull()?.let { names[it] = s.substring(i + 1) }
            } else if (p.type == Payload.Type.FILE) {
                p.asFile()?.asUri()?.let { incoming[p.id] = it }
                track += p.id
            }
        }

        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            val pid = u.payloadId
            if (pid !in track) return
            if (t0 == 0L) t0 = SystemClock.elapsedRealtime()
            prog[pid] = Pair(u.bytesTransferred, u.totalBytes)
            when (u.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    done += pid
                    incoming[pid]?.let { saveToDownloads(it, names[pid] ?: "hop_$pid") }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                    status.text = "Transfer failed. Move the phones closer and try again."
            }
            val got = prog.values.sumOf { it.first }
            val total = prog.values.sumOf { it.second }
            bar.progress = if (total > 0) (got * 100 / total).toInt() else 0
            val secs = maxOf(0.2, (SystemClock.elapsedRealtime() - t0) / 1000.0)
            speedTv.text = "${fmt(got / secs)}/s"
            if (id == outgoing && sentCount > 0 && done.size >= sentCount) {
                status.text = "Sent $sentCount file${if (sentCount > 1) "s" else ""}."
                ui.postDelayed({ client.disconnectFromEndpoint(id) }, 1500)
            }
        }
    }

    // ---------- saving received files ----------
    private fun saveToDownloads(src: Uri, name: String) {
        Thread {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hop")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val dst = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openInputStream(src)!!.use { i ->
                    contentResolver.openOutputStream(dst)!!.use { o -> i.copyTo(o, 1 shl 20) }
                }
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(dst, cv, null, null)
                try { contentResolver.delete(src, null, null) } catch (e: Exception) { }
                ui.post { status.text = "Saved $name to Downloads/Hop" }
            } catch (e: Exception) {
                ui.post { status.text = "Couldn't save $name: ${e.message}" }
            }
        }.start()
    }
}
