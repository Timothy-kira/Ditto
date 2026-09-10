package kira.ditto.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class DigiCrewMesh(
    context: Context,
    private val scope: CoroutineScope,
    private val diagnostic: ((String, String) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val nsdManager = runCatching {
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }.getOrNull()
    private val http by lazy { AetherHttp.shared }

    private val _events = MutableSharedFlow<DigiCrewMeshEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DigiCrewMeshEvent> = _events.asSharedFlow()

    private val _presence = MutableStateFlow<List<DigiCrewPeerPresence>>(emptyList())
    val presence: StateFlow<List<DigiCrewPeerPresence>> = _presence.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile private var nodeId: String = ""
    @Volatile private var ownerLabel: String = "Peer"
    @Volatile private var rooms: List<BoundRoom> = emptyList()

    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()
    private val relays = ConcurrentHashMap<String, RelaySession>()
    private var lanJob: Job? = null
    private var pingJob: Job? = null
    private var advertiseJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null

    fun bind(
        nodeId: String,
        ownerLabel: String,
        crews: List<DigiCrewRoom>,
        localPersonas: List<PersonaProfile>,
    ) {
        val nextNodeId = nodeId.ifBlank { this.nodeId.ifBlank { UUID.randomUUID().toString() } }
        val nextOwner = ownerLabel.ifBlank { "Peer" }
        val personasById = localPersonas.associateBy { it.id }
        val nextRooms = crews.filter { it.roomSecret.isNotBlank() }.map { crew ->
            BoundRoom(
                roomId = crew.id,
                secret = crew.roomSecret,
                offered = crew.memberPersonaIds.mapNotNull { personaId ->
                    val persona = personasById[personaId] ?: return@mapNotNull null
                    DigiCrewOfferedPersona(persona.id, persona.name)
                },
            )
        }
        if (this.nodeId == nextNodeId && this.ownerLabel == nextOwner && this.rooms == nextRooms) return
        this.nodeId = nextNodeId
        this.ownerLabel = nextOwner
        this.rooms = nextRooms
        scope.launch { restartTransports() }
    }

    fun publishUserChat(roomId: String, text: String) {
        publish(
            roomId = roomId,
            type = "chat",
            extra = JSONObject()
                .put("kind", "user")
                .put("speaker", ownerLabel)
                .put("text", text.trim()),
        )
    }

    fun publishPersonaChat(roomId: String, speakerName: String, text: String) {
        publish(
            roomId = roomId,
            type = "chat",
            extra = JSONObject()
                .put("kind", "persona")
                .put("speaker", speakerName)
                .put("text", text.trim()),
        )
    }

    private fun publish(roomId: String, type: String, extra: JSONObject) {
        val room = rooms.firstOrNull { it.roomId == roomId } ?: return
        val eventId = UUID.randomUUID().toString()
        seenEventIds.add(eventId)
        val envelope = JSONObject()
            .put("v", 1)
            .put("type", type)
            .put("event_id", eventId)
            .put("room_id", room.roomId)
            .put("peer_id", nodeId)
            .put("owner", ownerLabel)
            .put("ts", System.currentTimeMillis())
            .put("personas", JSONArray(room.offered.map { JSONObject().put("id", it.personaId).put("name", it.personaName) }))
        extra.keys().forEach { key -> envelope.put(key, extra.get(key)) }
        val packet = seal(room, envelope)
        relays.values.forEach { it.publish(room.topic, packet) }
    }

    private suspend fun restartTransports() {
        stopLanLocked()
        relays.values.forEach { it.close() }
        relays.clear()
        if (rooms.isEmpty() || nodeId.isBlank()) {
            _status.value = "Idle"
            return
        }
        ensureRelays()
        startLan()
        if (advertiseJob?.isActive != true) {
            advertiseJob = scope.launch {
                while (isActive) {
                    rooms.forEach { room ->
                        publish(room.roomId, "hello", JSONObject())
                    }
                    delay(20_000)
                }
            }
        }
        if (pingJob?.isActive != true) {
            pingJob = scope.launch {
                while (isActive) {
                    delay(20_000)
                    relays.values.forEach { it.ping() }
                    prunePresence()
                    refreshStatus()
                }
            }
        }
        refreshStatus()
    }

    private fun ensureRelays() {
        DefaultRelays.forEach { url ->
            val existing = relays[url]
            if (existing == null || !existing.isOpen) {
                existing?.close()
                relays[url] = RelaySession(url)
            }
        }
    }

    private fun startLan() {
        lanJob = scope.launch(Dispatchers.IO) {
            runCatching { bindLanServer() }
                .onFailure { diagnostic?.invoke("digicrew_lan_bind_failed", it.message.orEmpty()) }
        }
        runCatching { startNsd() }
    }

    private fun bindLanServer() {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) return
        val server = ServerSocket(0)
        serverSocket = server
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { handleLanSocket(client, outbound = false) }
        }
    }

    private fun startNsd() {
        val manager = nsdManager ?: return
        val port = serverSocket?.localPort ?: return
        stopNsd()
        val info = NsdServiceInfo().apply {
            serviceName = "dc${nodeId.filter { it.isLetterOrDigit() }.take(10)}"
            serviceType = NsdType
            setPort(port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        nsdRegistration = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == info.serviceName) return
                runCatching {
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host ?: return
                                val remotePort = serviceInfo.port
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        handleLanSocket(Socket(host, remotePort), outbound = true)
                                    }
                                }
                            }
                        },
                    )
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
        }
        nsdDiscovery = discovery
        runCatching { manager.discoverServices(NsdType, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    private fun handleLanSocket(socket: Socket, outbound: Boolean) {
        socket.soTimeout = 15_000
        socket.use { live ->
            val input = DataInputStream(live.getInputStream())
            val output = DataOutputStream(live.getOutputStream())
            if (outbound) {
                rooms.forEach { room ->
                    val hello = JSONObject()
                        .put("v", 1)
                        .put("type", "hello")
                        .put("event_id", UUID.randomUUID().toString())
                        .put("room_id", room.roomId)
                        .put("peer_id", nodeId)
                        .put("owner", ownerLabel)
                        .put("ts", System.currentTimeMillis())
                        .put("personas", JSONArray(room.offered.map {
                            JSONObject().put("id", it.personaId).put("name", it.personaName)
                        }))
                    writeLanFrame(output, seal(room, hello))
                }
            }
            while (true) {
                val frame = readLanFrame(input) ?: break
                ingestCiphertext(frame, via = "lan")
            }
        }
    }

    private fun stopLanLocked() {
        lanJob?.cancel()
        lanJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        stopNsd()
    }

    private fun stopNsd() {
        val manager = nsdManager ?: return
        nsdRegistration?.let { listener ->
            runCatching { manager.unregisterService(listener) }
        }
        nsdDiscovery?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        nsdRegistration = null
        nsdDiscovery = null
    }

    private fun ingestCiphertext(packet: String, via: String) {
        rooms.forEach { room ->
            val json = open(room, packet) ?: return@forEach
            ingestEnvelope(json, via)
        }
    }

    private fun ingestEnvelope(json: JSONObject, via: String) {
        val peerId = json.optString("peer_id")
        if (peerId.isBlank() || peerId == nodeId) return
        val eventId = json.optString("event_id").ifBlank { UUID.randomUUID().toString() }
        if (!seenEventIds.add(eventId)) return
        if (seenEventIds.size > 400) {
            seenEventIds.clear()
            seenEventIds.add(eventId)
        }
        val roomId = json.optString("room_id")
        val owner = json.optString("owner").ifBlank { "Peer" }
        val offered = json.optJSONArray("personas").toOffered()
        rememberPresence(
            DigiCrewPeerPresence(
                peerId = peerId,
                ownerLabel = owner,
                roomId = roomId,
                personas = offered,
                via = via,
            ),
        )
        _events.tryEmit(
            DigiCrewMeshEvent.Presence(
                roomId = roomId,
                peerId = peerId,
                eventId = "presence-$eventId",
                ownerLabel = owner,
                personas = offered,
                via = via,
            ),
        )
        if (json.optString("type") != "chat") return
        val text = json.optString("text").trim()
        if (text.isBlank()) return
        _events.tryEmit(
            DigiCrewMeshEvent.Chat(
                roomId = roomId,
                peerId = peerId,
                eventId = eventId,
                kind = json.optString("kind").ifBlank { "user" },
                speakerName = json.optString("speaker").ifBlank { owner },
                text = text,
            ),
        )
    }

    private fun rememberPresence(presence: DigiCrewPeerPresence) {
        _presence.value = (
            _presence.value.filterNot {
                it.peerId == presence.peerId && it.roomId == presence.roomId
            } + presence
            ).sortedBy { it.ownerLabel.lowercase() }
    }

    private fun prunePresence() {
        val cutoff = System.currentTimeMillis() - 90_000
        _presence.value = _presence.value.filter { it.lastSeenMillis >= cutoff }
    }

    private fun refreshStatus() {
        val relayLive = relays.values.count { it.isOpen }
        val peers = _presence.value.distinctBy { it.peerId }.size
        _status.value = when {
            rooms.isEmpty() -> "Idle"
            relayLive > 0 && peers > 0 -> "Relay $relayLive · peers $peers"
            relayLive > 0 -> "Relay connected · waiting for peers"
            else -> "Connecting mesh…"
        }
    }

    private inner class RelaySession(private val url: String) {
        @Volatile var isOpen: Boolean = false
            private set
        private var socket: WebSocket? = null
        private val buffer = ByteArrayOutputStream()
        private var packetId = 1

        init {
            val request = Request.Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(ByteString.of(*mqttConnect(nodeId)))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    synchronized(buffer) {
                        buffer.write(bytes.toByteArray())
                        drainMqtt(webSocket)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isOpen = false
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isOpen = false
                    diagnostic?.invoke("digicrew_relay_failed", "${url}: ${t.message}")
                }
            })
        }

        fun publish(topic: String, payload: String) {
            val live = socket ?: return
            if (!isOpen) return
            live.send(ByteString.of(*mqttPublish(topic, payload)))
        }

        fun ping() {
            socket?.send(ByteString.of(0xC0.toByte(), 0x00))
        }

        fun close() {
            isOpen = false
            socket?.close(1000, "done")
            socket = null
        }

        private fun drainMqtt(webSocket: WebSocket) {
            val data = buffer.toByteArray()
            var offset = 0
            while (offset < data.size) {
                if (data.size - offset < 2) break
                val type = data[offset].toInt() and 0xF0
                val remaining = decodeRemaining(data, offset + 1) ?: break
                val headerSize = remaining.second
                val packetEnd = offset + 1 + headerSize + remaining.first
                if (packetEnd > data.size) break
                when (type) {
                    0x20 -> {
                        isOpen = true
                        rooms.forEach { room ->
                            webSocket.send(ByteString.of(*mqttSubscribe(nextPacketId(), room.topic)))
                        }
                        refreshStatus()
                    }
                    0x30 -> handlePublish(data.copyOfRange(offset, packetEnd))
                }
                offset = packetEnd
            }
            buffer.reset()
            if (offset < data.size) buffer.write(data, offset, data.size - offset)
        }

        private fun handlePublish(packet: ByteArray) {
            var cursor = 1
            val remaining = decodeRemaining(packet, cursor) ?: return
            cursor += remaining.second
            if (cursor + 2 > packet.size) return
            val topicLength = ((packet[cursor].toInt() and 0xFF) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
            cursor += 2
            if (cursor + topicLength > packet.size) return
            cursor += topicLength
            val payload = packet.copyOfRange(cursor, packet.size).toString(Charsets.UTF_8)
            ingestCiphertext(payload, via = "relay")
        }

        private fun nextPacketId(): Int {
            packetId = if (packetId >= 0xFFFF) 1 else packetId + 1
            return packetId
        }
    }

    private data class BoundRoom(
        val roomId: String,
        val secret: String,
        val offered: List<DigiCrewOfferedPersona>,
    ) {
        val topic: String = "digicrew/v1/${sha256Hex("$roomId:$secret").take(32)}"
        val key: ByteArray = sha256Bytes(secret)
    }

    companion object {
        private const val NsdType = "_digicrew._tcp."
        private val DefaultRelays = listOf(
            "wss://broker.emqx.io:8084/mqtt",
            "wss://broker.hivemq.com:8884/mqtt",
        )

        private fun sha256Hex(value: String): String =
            sha256Bytes(value).joinToString("") { "%02x".format(it) }

        private fun sha256Bytes(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

        private fun seal(room: BoundRoom, json: JSONObject): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(room.key, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            return android.util.Base64.encodeToString(iv + encrypted, android.util.Base64.NO_WRAP)
        }

        private fun open(room: BoundRoom, packet: String): JSONObject? = runCatching {
            val raw = android.util.Base64.decode(packet, android.util.Base64.NO_WRAP)
            if (raw.size < 13) return null
            val iv = raw.copyOfRange(0, 12)
            val body = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(room.key, "AES"), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
        }.getOrNull()

        private fun writeMqttString(out: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write((bytes.size shr 8) and 0xFF)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }

        private fun encodeRemaining(length: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var value = length
            do {
                var encoded = value % 128
                value /= 128
                if (value > 0) encoded = encoded or 0x80
                out.write(encoded)
            } while (value > 0)
            return out.toByteArray()
        }

        private fun decodeRemaining(data: ByteArray, start: Int): Pair<Int, Int>? {
            var multiplier = 1
            var value = 0
            var index = start
            var encoded: Int
            do {
                if (index >= data.size) return null
                encoded = data[index].toInt() and 0xFF
                value += (encoded and 0x7F) * multiplier
                multiplier *= 128
                index++
                if (index - start > 4) return null
            } while (encoded and 0x80 != 0)
            return value to (index - start)
        }

        private fun mqttConnect(clientId: String): ByteArray {
            val payload = ByteArrayOutputStream()
            writeMqttString(payload, "MQTT")
            payload.write(4)
            payload.write(0x02)
            payload.write(0)
            payload.write(45)
            writeMqttString(payload, "dc-${clientId.filter { it.isLetterOrDigit() }.take(18)}")
            val body = payload.toByteArray()
            val packet = ByteArrayOutputStream()
            packet.write(0x10)
            packet.write(encodeRemaining(body.size))
            packet.write(body)
            return packet.toByteArray()
        }

        private fun mqttSubscribe(packetId: Int, topic: String): ByteArray {
            val payload = ByteArrayOutputStream()
            payload.write((packetId shr 8) and 0xFF)
            payload.write(packetId and 0xFF)
            writeMqttString(payload, topic)
            payload.write(0)
            val body = payload.toByteArray()
            val packet = ByteArrayOutputStream()
            packet.write(0x82)
            packet.write(encodeRemaining(body.size))
            packet.write(body)
            return packet.toByteArray()
        }

        private fun mqttPublish(topic: String, payload: String): ByteArray {
            val body = ByteArrayOutputStream()
            writeMqttString(body, topic)
            body.write(payload.toByteArray(Charsets.UTF_8))
            val bytes = body.toByteArray()
            val packet = ByteArrayOutputStream()
            packet.write(0x30)
            packet.write(encodeRemaining(bytes.size))
            packet.write(bytes)
            return packet.toByteArray()
        }

        private fun writeLanFrame(output: DataOutputStream, packet: String) {
            val bytes = packet.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        private fun readLanFrame(input: DataInputStream): String? = runCatching {
            val size = input.readInt()
            if (size <= 0 || size > 256_000) return null
            val bytes = ByteArray(size)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()

        private fun JSONArray?.toOffered(): List<DigiCrewOfferedPersona> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    val item = optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        add(DigiCrewOfferedPersona(id, name))
                    }
                }
            }
        }
    }
}
