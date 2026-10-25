package com.pageflip.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class MainActivity : Activity() {

    companion object {
        private const val DISCOVERY_PORT = 8135
        private const val DISCOVERY_TIMEOUT_MS = 2500
    }

    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var toggle: Button
    private lateinit var discover: Button

    private val prefs by lazy { getSharedPreferences("pageflip", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        toggle = findViewById(R.id.toggle)
        discover = findViewById(R.id.discover)

        host.setText(prefs.getString("host", ""))
        port.setText(prefs.getInt("port", 8134).toString())

        toggle.setOnClickListener { if (PageFlipService.running) stopRemote() else startRemote() }
        discover.setOnClickListener { discoverKindle() }
        findViewById<Button>(R.id.prev).setOnClickListener { sendTest(PageFlipService.ACTION_PREV) }
        findViewById<Button>(R.id.next).setOnClickListener { sendTest(PageFlipService.ACTION_NEXT) }
        findViewById<Button>(R.id.battery).setOnClickListener { requestBatteryExemption() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        PageFlipService.statusListener = { showStatus(it) }
        showStatus(PageFlipService.status)
    }

    override fun onPause() {
        PageFlipService.statusListener = null
        super.onPause()
    }

    // Con la app en pantalla los botones de volumen también pasan página.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (PageFlipService.running) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.repeatCount == 0) sendTest(PageFlipService.ACTION_NEXT)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.repeatCount == 0) sendTest(PageFlipService.ACTION_PREV)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showStatus(text: String) {
        status.text = text
        toggle.text = if (PageFlipService.running) "Detener" else "Conectar"
    }

    private fun startRemote() {
        val h = host.text.toString().trim()
        val p = port.text.toString().toIntOrNull() ?: 8134
        if (h.isEmpty()) {
            Toast.makeText(this, "Escribe la IP del Kindle o pulsa «Buscar Kindle»", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("host", h).putInt("port", p).apply()
        startForegroundService(
            Intent(this, PageFlipService::class.java)
                .putExtra(PageFlipService.EXTRA_HOST, h)
                .putExtra(PageFlipService.EXTRA_PORT, p)
        )
        toggle.text = "Detener"
    }

    private fun stopRemote() {
        stopService(Intent(this, PageFlipService::class.java))
        showStatus("Detenido")
    }

    private fun sendTest(action: String) {
        if (!PageFlipService.running) {
            Toast.makeText(this, "Primero pulsa «Conectar»", Toast.LENGTH_SHORT).show()
            return
        }
        PageFlipService.send(this, action)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Ya está permitido ✔", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Envía un broadcast UDP; el plugin de KOReader responde con su puerto. */
    private fun discoverKindle() {
        discover.isEnabled = false
        discover.text = "Buscando…"
        Thread {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("PageFlip:discovery").apply { acquire() }
            var found: Pair<String, Int>? = null
            var name = ""
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = DISCOVERY_TIMEOUT_MS
                    val msg = "PAGEFLIP_DISCOVER".toByteArray()
                    for (addr in broadcastAddresses()) {
                        try {
                            sock.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT))
                        } catch (_: Exception) {}
                    }
                    val buf = ByteArray(256)
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val reply = String(packet.data, 0, packet.length).trim().split(" ", limit = 3)
                    if (reply.size >= 2 && reply[0] == "PAGEFLIP_HERE") {
                        found = packet.address.hostAddress!! to (reply[1].toIntOrNull() ?: 8134)
                        name = reply.getOrNull(2) ?: ""
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            } finally {
                lock.release()
            }
            runOnUiThread {
                discover.isEnabled = true
                discover.text = "Buscar Kindle en la red"
                val f = found
                if (f != null) {
                    host.setText(f.first)
                    port.setText(f.second.toString())
                    Toast.makeText(this, "Encontrado $name en ${f.first}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        "No se encontró. ¿Está activado Page Flip en KOReader y el Kindle en la misma wifi?",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                iface.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
            }
        } catch (_: Exception) {}
        return result.distinct()
    }
}
