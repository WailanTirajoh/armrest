package io.github.wailantirajoh.armrest.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToLong

/** Identitas HP: deviceId + kunci ECDSA P-256 (Android Keystore di app, kunci biasa di test). */
interface DeviceCredentials {
    val deviceId: String
    val deviceName: String
    val publicKeyDer: ByteArray
    fun sign(payload: ByteArray): ByteArray
}

sealed interface ConnectTarget {
    val hostId: String
    val address: String
    val fingerprint: String

    data class Pair(val uri: PairingUri) : ConnectTarget {
        override val hostId get() = uri.hostId
        override val address get() = uri.address
        override val fingerprint get() = uri.fingerprint
    }

    data class Auth(override val hostId: String, override val address: String, override val fingerprint: String) : ConnectTarget
}

data class PairedHost(val hostId: String, val hostName: String, val fingerprint: String, val address: String)

sealed interface ClientFailure {
    data object FingerprintMismatch : ClientFailure
    data class PairRejected(val code: String) : ClientFailure
    data class AuthRejected(val code: String) : ClientFailure
    data class Protocol(val code: String) : ClientFailure
    data class Network(val message: String) : ClientFailure
    data object HeartbeatTimeout : ClientFailure
}

/**
 * Satu koneksi WebSocket TLS ke agent: handshake pair/auth, heartbeat, dan kirim input.
 * Callback listener dipanggil dari thread OkHttp.
 */
class AgentConnection(
    private val target: ConnectTarget,
    private val credentials: DeviceCredentials,
    private val listener: Listener,
    baseClient: OkHttpClient = OkHttpClient(),
    private val scheduler: ScheduledExecutorService = defaultScheduler,
) {
    interface Listener {
        fun onPaired(host: PairedHost) {}
        fun onAuthenticated() {}
        /** Kolom teks mulai atau berhenti fokus di Mac. Hanya dikirim setelah diminta lewat [sendSettings]. */
        fun onTextFocus(focused: Boolean) {}
        /** Status aliran layar dari Mac, mis. [ControlMessage.SCREEN_DENIED]. */
        fun onScreenStatus(state: String) {}
        /** Paket video layar. Frame sudah dikonfirmasi ke Mac sebelum callback ini. */
        fun onScreenPacket(packet: ScreenPacket) {}
        /** Posisi kursor di video layar, 0–1 dari kiri atas. */
        fun onScreenCursor(x: Double, y: Double) {}
        /** Volume output komputer; `level` null = tidak bisa diatur. Hanya setelah diminta lewat [sendSettings]. */
        fun onVolumeStatus(level: Double?, muted: Boolean) {}
        /** Koneksi selesai. `failure` null kalau ditutup normal. */
        fun onEnded(failure: ClientFailure?) {}
    }

    @Volatile
    var isAuthenticated = false
        private set

    /** Platform komputer dari `auth_result`; agent lama selalu macOS. */
    @Volatile
    var platform: String = ProtocolConstants.PLATFORM_MACOS
        private set

    /** Fitur opsional agent dari `auth_result`; kosong untuk agent lama. */
    @Volatile
    var features: Set<String> = emptySet()
        private set

    /** Alamat hardware komputer dari `auth_result`, untuk Wake-on-LAN. null untuk agent lama. */
    @Volatile
    var macAddress: String? = null
        private set

    private val trustManager = PinnedTrustManager(target.fingerprint)
    private val webSocket: WebSocket
    // Callback onOpen bisa datang sebelum `webSocket` selesai di-assign di init, jadi simpan dari callback.
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastReceived = System.currentTimeMillis()
    @Volatile private var failure: ClientFailure? = null
    private var hostId = target.hostId
    private var heartbeat: ScheduledFuture<*>? = null
    private var ended = false

    init {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val client = baseClient.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            // Identitas Mac dijamin oleh pinning fingerprint, bukan nama host.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
        webSocket = client.newWebSocket(Request.Builder().url("https://${target.address}").build(), SocketListener())
    }

    /** false kalau belum terautentikasi atau antrean kirim penuh. */
    fun sendInput(message: InputMessage): Boolean =
        isAuthenticated && webSocket.send(message.encode().toByteString())

    fun sendSettings(sensitivity: Double, scrollSpeed: Double, focusUpdates: Boolean, volumeUpdates: Boolean = false) {
        if (isAuthenticated) send(ControlMessage.Settings(sensitivity, scrollSpeed, focusUpdates, volumeUpdates))
    }

    /**
     * Minta video layar Mac, atau minta keyframe kalau sudah berjalan. Ukuran maksimum dalam piksel. Posisi kursor
     * selalu diminta; agent sebelum v0.7 mengabaikannya.
     */
    fun requestScreen(maxWidth: Int, maxHeight: Int) {
        if (isAuthenticated) send(ControlMessage.Screen(true, maxWidth, maxHeight, cursor = true))
    }

    /** Naik (positif) atau turun sekian langkah 1/16. Diabaikan kalau agent tidak mendukung volume. */
    fun changeVolume(steps: Int) = sendVolume(ControlMessage.Volume(step = steps))

    /** Dibulatkan ke 4 desimal: nilai slider (Float) kalau tidak menjadi 0.2489316165447235. */
    fun setVolume(level: Double) = sendVolume(ControlMessage.Volume(level = (level.coerceIn(0.0, 1.0) * 10_000).roundToLong() / 10_000.0))

    fun setMuted(muted: Boolean) = sendVolume(ControlMessage.Volume(muted = muted))

    // Agent lama menutup koneksi saat menerima pesan yang tidak dikenal, jadi hanya dikirim kalau fiturnya ada.
    private fun sendVolume(message: ControlMessage.Volume) {
        if (isAuthenticated && ProtocolConstants.FEATURE_VOLUME in features) send(message)
    }

    /**
     * Tidurkan, mulai ulang, atau matikan komputer ([ControlMessage.POWER_SHUTDOWN] dst.). Diabaikan kalau agent
     * tidak mendukung fitur power. Konfirmasi ke user dilakukan UI sebelum memanggil ini.
     */
    fun power(action: String) {
        if (isAuthenticated && ProtocolConstants.FEATURE_POWER in features) send(ControlMessage.Power(action))
    }

    fun stopScreen() {
        if (isAuthenticated) send(ControlMessage.Screen(false))
    }

    /** Byte yang masih menunggu dikirim; dipakai untuk menggabung gerakan saat jaringan lambat. */
    fun queueSize(): Long = webSocket.queueSize()

    fun close() {
        webSocket.close(1000, null)
    }

    private fun send(message: ControlMessage) {
        (socket ?: webSocket).send(message.toJson())
    }

    private fun fail(reason: ClientFailure) {
        if (failure == null) failure = reason
        (socket ?: webSocket).cancel()
    }

    private fun handle(message: ControlMessage?) {
        when (message) {
            is ControlMessage.PairResult -> {
                val pairTarget = target as? ConnectTarget.Pair ?: return fail(ClientFailure.Protocol("bad_message"))
                if (!message.ok) return fail(ClientFailure.PairRejected(message.error ?: "unknown"))
                hostId = message.hostId ?: hostId
                listener.onPaired(PairedHost(hostId, message.hostName ?: pairTarget.uri.hostName, target.fingerprint, target.address))
            }
            is ControlMessage.Challenge -> {
                val nonce = AuthCrypto.fromBase64(message.nonce) ?: return fail(ClientFailure.Protocol("bad_message"))
                val signature = credentials.sign(AuthCrypto.payload(nonce, hostId, credentials.deviceId))
                send(ControlMessage.Auth(AuthCrypto.base64(signature)))
            }
            is ControlMessage.AuthResult -> {
                if (!message.ok) return fail(ClientFailure.AuthRejected(message.error ?: "unknown"))
                features = message.features.toSet()
                platform = message.platform ?: ProtocolConstants.PLATFORM_MACOS
                macAddress = message.mac?.let(WakeOnLan::normalize)
                isAuthenticated = true
                listener.onAuthenticated()
            }
            is ControlMessage.Focus -> if (isAuthenticated) listener.onTextFocus(message.text)
            is ControlMessage.ScreenStatus -> if (isAuthenticated) listener.onScreenStatus(message.state)
            is ControlMessage.ScreenCursor -> if (isAuthenticated) listener.onScreenCursor(message.x, message.y)
            is ControlMessage.VolumeStatus -> if (isAuthenticated) listener.onVolumeStatus(message.level, message.muted)
            is ControlMessage.Ping -> send(ControlMessage.Pong(message.ts))
            is ControlMessage.Error -> fail(ClientFailure.Protocol(message.error))
            else -> Unit
        }
    }

    private fun startHeartbeat() {
        heartbeat = scheduler.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - lastReceived > HEARTBEAT_TIMEOUT_MS) {
                fail(ClientFailure.HeartbeatTimeout)
            } else {
                send(ControlMessage.Ping(System.currentTimeMillis()))
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun end(reason: ClientFailure?) {
        synchronized(this) {
            if (ended) return
            ended = true
        }
        heartbeat?.cancel(false)
        isAuthenticated = false
        listener.onEnded(failure ?: reason)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            lastReceived = System.currentTimeMillis()
            val mode = if (target is ConnectTarget.Pair) ControlMessage.MODE_PAIR else ControlMessage.MODE_AUTH
            send(ControlMessage.Hello(ProtocolConstants.PROTOCOL_VERSION, credentials.deviceId, mode))
            if (target is ConnectTarget.Pair) {
                send(ControlMessage.PairRequest(target.uri.token, credentials.deviceName, AuthCrypto.base64(credentials.publicKeyDer)))
            }
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastReceived = System.currentTimeMillis()
            handle(ControlMessage.parse(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            lastReceived = System.currentTimeMillis()
            if (!isAuthenticated) return
            val packet = ScreenPacket.parse(bytes.toByteArray()) ?: return
            // Konfirmasi begitu diterima: Mac menahan frame berikutnya kalau terlalu banyak yang belum dikonfirmasi.
            if (packet is ScreenPacket.Frame) send(ControlMessage.ScreenAck(packet.seq))
            listener.onScreenPacket(packet)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            end(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end(if (trustManager.mismatch) ClientFailure.FingerprintMismatch else ClientFailure.Network(t.message ?: t.javaClass.simpleName))
        }
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val HEARTBEAT_TIMEOUT_MS = 15_000L

        private val defaultScheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "armrest-heartbeat").apply { isDaemon = true }
            }
        }
    }
}

/** Hanya menerima sertifikat dengan fingerprint yang dibawa QR. */
class PinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    @Volatile
    var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Tidak ada sertifikat")
        val actual = AuthCrypto.fingerprint(leaf.encoded)
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedFingerprint.toByteArray())) {
            mismatch = true
            throw CertificateException("Fingerprint sertifikat tidak cocok")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("Tidak dipakai")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
