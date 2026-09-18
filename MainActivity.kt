package com.nearcall.v2

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val serviceId = "com.nearcall.v2.mesh"
    private val strategy = Strategy.P2P_CLUSTER
    private val requestCode = 9001
    private val connected = ConcurrentHashMap<String, String>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val endpointToName = ConcurrentHashMap<String, String>()
    private lateinit var logView: TextView
    private lateinit var usersView: TextView
    private lateinit var messageInput: EditText
    private var recording = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(this@MainActivity)
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { log("Accept failed: ${it.message}") }
            endpointToName[endpointId] = info.endpointName
            log("Connection requested by ${info.endpointName}")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected[endpointId] = endpointToName[endpointId] ?: endpointId.take(6)
                log("Connected: ${connected[endpointId]}")
                refreshUsers()
            } else {
                log("Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            log("Disconnected: ${connected.remove(endpointId) ?: endpointId.take(6)}")
            refreshUsers()
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointToName[endpointId] = info.endpointName
            log("Found: ${info.endpointName}")
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myName(), endpointId, connectionLifecycle)
                .addOnFailureListener { log("Request failed: ${it.message}") }
        }

        override fun onEndpointLost(endpointId: String) {
            log("Lost: ${endpointToName[endpointId] ?: endpointId.take(6)}")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val text = String(bytes, StandardCharsets.UTF_8)
            handlePacket(endpointId, text)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissionsIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "NearCall V2"
            textSize = 26f
        }
        val status = TextView(this).apply { text = "Offline mesh prototype" }
        usersView = TextView(this).apply { text = "Connected: 0" }
        messageInput = EditText(this).apply { hint = "Message" }
        logView = TextView(this).apply { text = "Log:\n"; textSize = 14f }
        val start = Button(this).apply { text = "Start Mesh"; setOnClickListener { startMesh() } }
        val stop = Button(this).apply { text = "Stop Mesh"; setOnClickListener { stopMesh() } }
        val send = Button(this).apply { text = "Broadcast Message"; setOnClickListener { broadcastText(messageInput.text.toString()) } }
        val call = Button(this).apply { text = "Start Voice Call (direct peer)"; setOnClickListener { toggleVoice() } }
        root.addView(title); root.addView(status); root.addView(usersView)
        root.addView(start); root.addView(stop); root.addView(messageInput); root.addView(send); root.addView(call)
        root.addView(logView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        val all = if (android.os.Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else if (android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.RECORD_AUDIO)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        }
        all.forEach { if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) needed += it }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), requestCode)
    }

    private fun myName(): String = "NearCall-${android.os.Build.MODEL}-${UUID.nameUUIDFromBytes(android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID).toByteArray()).toString().take(6)}"

    private fun startMesh() {
        val client = Nearby.getConnectionsClient(this)
        client.startAdvertising(myName(), serviceId, connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { log("Advertising started") }
            .addOnFailureListener { log("Advertising error: ${it.message}") }
        client.startDiscovery(serviceId, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { log("Discovery started") }
            .addOnFailureListener { log("Discovery error: ${it.message}") }
    }

    private fun stopMesh() {
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        connected.clear(); endpointToName.clear(); refreshUsers()
        log("Mesh stopped")
    }

    private fun broadcastText(text: String) {
        if (text.isBlank()) return
        val packet = packet("TEXT", text)
        broadcast(packet)
        log("Me: $text")
        messageInput.text.clear()
    }

    private fun packet(type: String, body: String, ttl: Int = 6): String = JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("src", myName())
        .put("type", type)
        .put("ttl", ttl)
        .put("body", body)
        .toString()

    private fun handlePacket(from: String, raw: String) {
        val obj = try { JSONObject(raw) } catch (_: Exception) { return }
        val id = obj.optString("id")
        if (id.isBlank() || !seen.add(id)) return
        val type = obj.optString("type")
        val body = obj.optString("body")
        if (type == "TEXT") {
            runOnUiThread { log("${obj.optString("src", "Peer")}: $body") }
        }
        if (type == "VOICE") {
            val data = android.util.Base64.decode(body, android.util.Base64.NO_WRAP)
            playPcm(data)
        }
        val ttl = obj.optInt("ttl", 0)
        if (ttl > 0) {
            obj.put("ttl", ttl - 1)
            relay(obj.toString(), from)
        }
    }

    private fun relay(raw: String, except: String) {
        connected.keys.filter { it != except }.forEach { send(it, raw) }
    }

    private fun broadcast(raw: String) { connected.keys.forEach { send(it, raw) } }

    private fun send(endpointId: String, raw: String) {
        Nearby.getConnectionsClient(this).sendPayload(
            endpointId, Payload.fromBytes(raw.toByteArray(StandardCharsets.UTF_8))
        ).addOnFailureListener { log("Send failed: ${it.message}") }
    }

    private fun toggleVoice() {
        if (recording) stopVoice() else startVoice()
    }

    private fun startVoice() {
        if (connected.isEmpty()) { log("Connect to at least one peer first"); return }
        val sampleRate = 8000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 2048))
        recording = true
        audioRecord?.startRecording()
        log("Voice sending started (prototype PCM)")
        thread(start = true, name = "NearCallAudio") {
            val buf = ByteArray(640)
            while (recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                    val p = packet("VOICE", b64, ttl = 1)
                    broadcast(p)
                }
            }
        }
    }

    private fun stopVoice() {
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release(); audioRecord = null
        log("Voice sending stopped")
    }

    private fun playPcm(data: ByteArray) {
        val sampleRate = 8000
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (audioTrack == null) {
            audioTrack = AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 2048), AudioTrack.MODE_STREAM)
            audioTrack?.play()
        }
        audioTrack?.write(data, 0, data.size)
    }

    private fun refreshUsers() {
        runOnUiThread { usersView.text = "Connected: ${connected.size}\n" + connected.values.joinToString("\n") }
    }

    private fun log(s: String) {
        runOnUiThread {
            logView.append("$s\n")
            if (logView.lineCount > 120) logView.text = logView.text.split("\n").takeLast(80).joinToString("\n")
        }
    }

    override fun onDestroy() {
        stopVoice(); stopMesh(); super.onDestroy()
    }
}
