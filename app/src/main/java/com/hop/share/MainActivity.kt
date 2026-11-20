package com.hop.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
    data class Pick(val uri: Uri, val name: String, val size: Long)
    private class Row(val label: String, val ok: () -> Boolean, val btn: String, val fix: () -> Unit) {
        lateinit var tv: TextView
        lateinit var b: Button
        lateinit var check: TextView
    }

    private val svc = "com.hop.share"
    private val bg = Color.parseColor("#F5F6F8")
    private val ink = Color.parseColor("#111827")
    private val muted = Color.parseColor("#6B7280")
    private val line = Color.parseColor("#E5E7EB")
    private val accent = Color.parseColor("#2B5CFF")
    private val good = Color.parseColor("#16A34A")
    private val soft = Color.parseColor("#EAF0FF")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var client: ConnectionsClient
    private lateinit var myName: String
    private lateinit var radar: RadarView
    private lateinit var status: TextView
    private lateinit var reqCard: LinearLayout
    private lateinit var filesTitle: TextView
    private lateinit var clearBtn: TextView
    private lateinit var filesList: LinearLayout
    private lateinit var xferCard: LinearLayout
    private lateinit var speedTv: TextView
    private lateinit var bar: ProgressBar
    private val ui = Handler(Looper.getMainLooper())
    private val rows = ArrayList<Row>()

    private val peers = LinkedHashMap<String, Peer>()
    private val files = ArrayList<Pick>()
    private var started = false
    private var askedPerm = false
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
        renderFiles()
        if (missing().isNotEmpty()) { askedPerm = true; requestPermissions(needed().toTypedArray(), 1) }
    }

    override fun onResume() { super.onResume(); refreshState() }

    override fun onDestroy() {
        client.stopAllEndpoints()
        super.onDestroy()
    }

    // ---------- permissions and switches ----------
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
        refreshState()
    }

    private fun askPermissions() {
        if (!askedPerm || needed().any { shouldShowRequestPermissionRationale(it) }) {
            askedPerm = true
            requestPermissions(needed().toTypedArray(), 1)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun isWifiOn() =
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    private fun isBtOn() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    private fun isLocOn() = getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /** Shows the requirements card only while something is still off; starts Hop once all is ready. */
    private fun refreshState() {
        if (!::reqCard.isInitialized) return
        var all = true
        for (r in rows) {
            val ok = r.ok()
            all = all && ok
            r.b.visibility = if (ok) View.GONE else View.VISIBLE
            r.check.visibility = if (ok) View.VISIBLE else View.GONE
            r.tv.setTextColor(if (ok) muted else ink)
        }
        reqCard.visibility = if (all) View.GONE else View.VISIBLE
        if (all && !started) { started = true; startNearby(); refresh() }
        if (!all) status.text = "Turn on the items above to find nearby phones."
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    private fun tv(t: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; stateListAnimator = null
        minHeight = 0; minimumHeight = 0; setPadding(dp(16), dp(8), dp(16), dp(8))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (filled) { setTextColor(Color.WHITE); background = shape(accent, 24) }
        else { setTextColor(accent); background = shape(soft, 24) }
        setOnClickListener { onClick() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.WHITE, 22)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun fmt(b: Double): String = when {
        b > 1e9 -> "%.2f GB".format(b / 1e9)
        b > 1e6 -> "%.1f MB".format(b / 1e6)
        b > 1e3 -> "%d KB".format((b / 1e3).toInt())
        else -> "%d B".format(b.toInt())
    }

    // ---------- UI ----------
    private fun buildUi() {
        window.statusBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        // header: app name + this phone's name
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        head.addView(tv("Hop", 30f, ink, true), lp(0, WRAP, 1f))
        val me = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.WHITE, 24); setPadding(dp(12), dp(7), dp(14), dp(7))
        }
        me.addView(tv("\u25CF ", 12f, good))
        me.addView(tv(myName.take(18), 13f, ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        head.addView(me)
        col.addView(head)

        // requirements card, shown inside the app only while something is off
        reqCard = card()
        reqCard.addView(tv("Almost ready", 18f, ink, true))
        reqCard.addView(tv("Turn these on so Hop can find phones and send fast.", 14f, muted).apply { setPadding(0, dp(2), 0, dp(6)) })
        rows += Row("Wi\u2011Fi", { isWifiOn() }, "Turn on") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        rows += Row("Bluetooth", { isBtOn() }, "Turn on") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        rows += Row("Location", { isLocOn() }, "Turn on") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        rows += Row("Permissions", { missing().isEmpty() }, "Allow") { askPermissions() }
        rows.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            r.tv = tv(r.label, 16f, ink)
            r.b = pill(r.btn, true) { r.fix() }
            r.check = tv("\u2713  On", 14f, good, true)
            row.addView(r.tv, lp(0, WRAP, 1f)); row.addView(r.b); row.addView(r.check)
            reqCard.addView(row)
            if (i < rows.size - 1) reqCard.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        }
        col.addView(reqCard)

        // radar + status
        radar = RadarView(this)
        radar.onTap = { send(it) }
        col.addView(radar, lp(MATCH, dp(300)))
        status = tv("", 15f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(16)) }
        col.addView(status, lp(MATCH, WRAP))

        // files you picked
        val filesCard = card()
        val fh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        filesTitle = tv("Files to send", 18f, ink, true)
        clearBtn = tv("Clear all", 14f, accent, true).apply {
            setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener { files.clear(); renderFiles() }
        }
        fh.addView(filesTitle, lp(0, WRAP, 1f)); fh.addView(clearBtn)
        filesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filesCard.addView(fh); filesCard.addView(filesList)
        filesCard.addView(pill("+  Add files", true) { pick() }, lp(MATCH, dp(48)).apply { topMargin = dp(10) })
        col.addView(filesCard)

        // transfer progress
        xferCard = card().apply { visibility = View.GONE }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        speedTv = tv("", 13f, muted).apply { setPadding(0, dp(6), 0, 0) }
        xferCard.addView(tv("Transfer", 16f, ink, true))
        xferCard.addView(bar, lp(MATCH, dp(10)).apply { topMargin = dp(10) })
        xferCard.addView(speedTv)
        col.addView(xferCard)

        val sv = ScrollView(this).apply {
            setBackgroundColor(bg); isFillViewport = true; fitsSystemWindows = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        sv.addView(col)
        setContentView(sv)
    }

    private fun renderFiles() {
        filesList.removeAllViews()
        val n = files.size
        filesTitle.text = if (n == 0) "Files to send" else "Files to send  \u00B7  $n"
        clearBtn.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (n == 0) {
            filesList.addView(tv("Nothing picked yet. Add files, then tap a phone on the radar.", 14f, muted).apply {
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        files.forEachIndexed { i, f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val ext = f.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }
            val icon = tv(ext, 11f, accent, true).apply { gravity = Gravity.CENTER; background = shape(soft, 12) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(tv(f.name, 15f, ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
            info.addView(tv(fmt(f.size.toDouble()), 12f, muted))
            val x = tv("\u2715", 16f, muted).apply {
                setPadding(dp(14), dp(8), dp(2), dp(8))
                contentDescription = "Remove ${f.name}"
                setOnClickListener { files.remove(f); renderFiles() }
            }
            row.addView(icon, lp(dp(44), dp(44)).apply { rightMargin = dp(12) })
            row.addView(info, lp(0, WRAP, 1f)); row.addView(x)
            filesList.addView(row)
            if (i < n - 1) filesList.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        }
    }

    private fun refresh() {
        radar.peers = peers.values.toList()
        if (started && outgoing == null && done.isEmpty()) {
            val n = peers.size
            status.text = if (n == 0) "Looking for nearby phones. Open Hop on the other phone."
            else "$n phone${if (n > 1) "s" else ""} nearby. Tap one to send."
        }
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
            val uris = if (c != null) (0 until c.itemCount).map { c.getItemAt(it).uri } else listOfNotNull(data.data)
            for (u in uris) if (files.none { it.uri == u }) files += describe(u)
            renderFiles()
        }
    }

    private fun describe(u: Uri): Pick {
        var n = "file"
        var s = 0L
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i) ?: n
                val j = c.getColumnIndex(OpenableColumns.SIZE)
                if (j >= 0 && !c.isNull(j)) s = c.getLong(j)
            }
        }
        return Pick(u, n, s)
    }

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
        if (files.isEmpty()) { Toast.makeText(this, "Add files to send first", Toast.LENGTH_SHORT).show(); return }
        track.clear(); done.clear(); prog.clear(); names.clear(); incoming.clear()
        t0 = 0L; bar.progress = 0; speedTv.text = ""
        xferCard.visibility = View.VISIBLE
        outgoing = p.id
        status.text = "Connecting to ${p.name}\u2026"
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
            if (id == outgoing) sendFiles(id) else status.text = "Connected. Receiving\u2026"
        }
        override fun onDisconnected(id: String) {
            if (id == outgoing) outgoing = null
            ui.post { if (done.isEmpty()) refresh() }
        }
    }

    private fun sendFiles(id: String) {
        sentCount = 0
        for (f in files.toList()) {
            val pfd = contentResolver.openFileDescriptor(f.uri, "r") ?: continue
            val p = Payload.fromFile(pfd)
            track += p.id
            sentCount++
            // The receiver learns the real file name from this small message.
            client.sendPayload(id, Payload.fromBytes("${p.id}|${f.name}".toByteArray()))
            client.sendPayload(id, p)
        }
        status.text = "Sending $sentCount file${if (sentCount > 1) "s" else ""}\u2026"
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
            xferCard.visibility = View.VISIBLE
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
            speedTv.text = "${fmt(got.toDouble())} of ${fmt(total.toDouble())}  \u00B7  ${fmt(got / secs)}/s"
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
