package io.openflux.android.core

import android.os.ParcelFileDescriptor
import io.openflux.bridge.mobile.Mobile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Moves IPv4 packets between Android's TUN interface and the core: TCP and
 * UDP go through the tunnel (Mobile.send / Mobile.read), DNS queries are
 * answered by [dnsServer] directly from the phone, as the Java app did.
 * [onFailure] reports a broken interface once.
 */
internal class PacketTunnel(
    private val tun: ParcelFileDescriptor,
    private val dnsServers: List<String>,
    private val sent: AtomicLong,
    private val received: AtomicLong,
    private val onProblem: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @Volatile private var active = true
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val outputLock = Any()

    /** The first usable resolver, for the message the user is shown. */
    private val dnsServer: String get() = dnsServers.firstOrNull() ?: "?"

    // More than four: every app on the phone resolves through here while the
    // tunnel holds the default route, and a page routinely opens ten or twenty
    // names at once. Four at a time with a timeout each was a queue, not a pool.
    private val dnsWorkers = Executors.newFixedThreadPool(DNS_WORKERS)

    fun start() {
        thread(name = "openflux-tun-out", isDaemon = true) { readOutgoing() }
        thread(name = "openflux-tun-in", isDaemon = true) { writeIncoming() }
    }

    fun close() {
        if (!active) return
        active = false
        dnsWorkers.shutdownNow()
        // Closing the descriptor wakes the reader blocked on it.
        runCatching { tun.close() }
    }

    private fun readOutgoing() {
        val buffer = ByteArray(32767)
        var consecutiveErrors = 0
        try {
            while (active) {
                val length = input.read(buffer)
                if (length <= 0) {
                    // A non-blocking interface (Android before 10) has nothing yet.
                    Thread.sleep(2)
                    continue
                }
                val packet = buffer.copyOf(length)
                when {
                    isIpv4UdpDns(packet) -> runCatching { dnsWorkers.execute { forwardDns(packet) } }
                    isIpv4Tcp(packet) || isIpv4Udp(packet) -> {
                        val error = Mobile.send(packet)
                        if (error.isNullOrEmpty()) {
                            consecutiveErrors = 0
                            sent.addAndGet(packet.size.toLong())
                        } else {
                            // The TUN holds the default route, so every app on
                            // the phone sends its packets here. The core
                            // refusing them used to be thrown away silently:
                            // the UI polls Mobile.isConnected() once a second,
                            // which knows nothing about whether packets are
                            // moving, so the app said "Подключено" with a
                            // speed of 0 while the phone had no route to the
                            // Internet at all. A run of refusals is the core
                            // being unable to forward, which is a failure the
                            // user can act on - so it is reported once.
                            if (++consecutiveErrors == MAX_CONSECUTIVE_SEND_ERRORS) {
                                onFailure("Ядро перестало передавать пакеты: $error")
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (active) onFailure("Чтение VPN-интерфейса: ${e.message}")
        } catch (_: InterruptedException) {
        }
    }

    private fun writeIncoming() {
        try {
            while (active) {
                // Drain the whole queue before sleeping. One Mobile.read() per
                // loop iteration is one JNI crossing and one TUN write syscall
                // per packet, and every empty poll costs a 2 ms sleep, so the
                // inbound side capped itself at a few hundred packets a
                // second no matter how fast the document transport was.
                var drained = false
                while (active) {
                    val packet = Mobile.read() ?: break
                    if (packet.isEmpty()) break
                    inject(packet)
                    received.addAndGet(packet.size.toLong())
                    drained = true
                }
                if (!drained) Thread.sleep(POLL_IDLE_MS)
            }
        } catch (e: IOException) {
            if (active) onFailure("Запись в VPN-интерфейс: ${e.message}")
        } catch (_: InterruptedException) {
        }
    }

    private fun inject(packet: ByteArray) {
        synchronized(outputLock) { if (active) output.write(packet) }
    }

    /** Answers the captured query by relaying it to [dnsServer] over plain UDP. */
    private fun forwardDns(request: ByteArray) {
        val ipHeader = (request[0].toInt() and 0x0f) * 4
        val dnsOffset = ipHeader + 8
        val udpLength = unsignedShort(request, ipHeader + 4)
        if (dnsOffset > request.size || udpLength < 8 || ipHeader + udpLength > request.size) return
        val query = request.copyOfRange(dnsOffset, ipHeader + udpLength)
        try {
            val answer = queryDns(query)
            if (answer == null || answer.isEmpty()) {
                // No answer is worse than a refusal: the resolver that never
                // answers is indistinguishable from a page that hangs, and
                // Android's netd keeps waiting for it. SERVFAIL makes the
                // lookup fail at once, so the browser can try another name or
                // show an error instead of sitting there.
                if (active) onProblem("DNS: сервер $dnsServer не ответил")
                inject(dnsResponse(request, servfail(query)))
                if (active) onProblem("")
                return
            }
            inject(dnsResponse(request, answer))
            // Clear it, and only after the answer is on its way to the TUN.
            // onProblem was write-only: the first failed lookup stuck its text
            // on the notification for the rest of the connection, so a single
            // timeout kept claiming DNS was broken long after it had recovered.
            if (active) onProblem("")
        } catch (e: IOException) {
            if (active) onProblem("DNS: ${e.message}")
        }
    }

    private fun queryDns(query: ByteArray): ByteArray? {
        // Every resolver in turn. A single one that is filtered or slow - a
        // captive portal, a carrier that drops 53, an IPv6-only resolver that
        // never answers over IPv4 - made every lookup on the phone queue
        // behind it, four at a time, each waiting out its timeout. That reads
        // as "pages do not open" with no error anywhere in the browser.
        for (server in dnsServers) {
            val answer = queryDns(query, server) ?: continue
            if (answer.isNotEmpty()) return answer
        }
        return null
    }

    private fun queryDns(query: ByteArray, server: String): ByteArray? = try {
        DatagramSocket().use { socket ->
            // Short on purpose: a resolver that is not going to answer should
            // fail fast so the next one is tried, not hold the queue.
            socket.soTimeout = DNS_TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            buffer.copyOf(response.length)
        }
    } catch (_: SocketTimeoutException) {
        null
    } catch (_: Exception) {
        // An unresolvable server, a refused send, a malformed reply: try the next.
        null
    }

    private companion object {
        const val DNS_WORKERS = 16

        /** Short: a resolver that is not going to answer should fail fast. */
        const val DNS_TIMEOUT_MS = 1500

        /** Long enough that one empty queue is noise, not a broken core. */
        const val POLL_IDLE_MS = 20L

        /**
         * Consecutive refused sends before the tunnel is declared failed.
         *
         * The TUN holds the default route, so the core refusing packets is the
         * whole phone losing its way out while the UI reports "Подключено" -
         * the liveness signal it polls knows nothing about packets moving.
         */
        const val MAX_CONSECUTIVE_SEND_ERRORS = 25

        /**
         * A DNS reply that says "no" instead of never arriving.
         *
         * Android's resolver waits for an answer that a blackholed UDP packet
         * will never bring, so a failed lookup looks exactly like a page that
         * hangs. RCODE 2 ends the wait immediately and lets the browser report
         * something or try another name.
         */
        fun servfail(query: ByteArray): ByteArray {
            if (query.size < 12) return query
            val reply = query.copyOf()
            // QR=1, OPCODE and RD copied from the query, RA=1; the rest is the
            // standard header for a negative answer.
            reply[2] = ((query[2].toInt() and 0x01) shl 7).toByte()
            reply[3] = 0x83.toByte() // RA=1, RCODE=2 (SERVFAIL)
            reply[4] = 0
            reply[5] = 0 // no answers
            reply[6] = 0; reply[7] = 0
            reply[8] = 0; reply[9] = 0 // no authority
            reply[10] = 0; reply[11] = 0 // no additional
            return reply
        }

        fun isIpv4Tcp(p: ByteArray) = p.size >= 20 && (p[0].toInt() ushr 4) == 4 && (p[9].toInt() and 0xff) == 6

        /** Non-DNS UDP: the exit forwards it like any other packet. */
        fun isIpv4Udp(p: ByteArray) =
            p.size >= 20 && (p[0].toInt() ushr 4) == 4 && (p[9].toInt() and 0xff) == 17 && !isIpv4UdpDns(p)

        fun isIpv4UdpDns(p: ByteArray): Boolean {
            if (p.size < 28 || (p[0].toInt() ushr 4) != 4 || (p[9].toInt() and 0xff) != 17) return false
            val header = (p[0].toInt() and 0x0f) * 4
            return header >= 20 && p.size >= header + 8 && unsignedShort(p, header + 2) == 53
        }

        fun dnsResponse(request: ByteArray, dns: ByteArray): ByteArray {
            val requestHeader = (request[0].toInt() and 0x0f) * 4
            val response = ByteArray(20 + 8 + dns.size)
            response[0] = 0x45
            response[1] = request[1]
            putShort(response, 2, response.size)
            response[4] = request[4]
            response[5] = request[5]
            response[8] = 64
            response[9] = 17
            System.arraycopy(request, 16, response, 12, 4)
            System.arraycopy(request, 12, response, 16, 4)
            putShort(response, 10, checksum(response, 0, 20))
            putShort(response, 20, 53)
            putShort(response, 22, unsignedShort(request, requestHeader))
            putShort(response, 24, 8 + dns.size)
            // A zero UDP checksum is valid for IPv4.
            putShort(response, 26, 0)
            System.arraycopy(dns, 0, response, 28, dns.size)
            return response
        }

        fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            while (i < offset + length) {
                val high = bytes[i].toInt() and 0xff
                val low = if (i + 1 < offset + length) bytes[i + 1].toInt() and 0xff else 0
                sum += ((high shl 8) or low).toLong()
                while (sum and 0xffff0000L != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
                i += 2
            }
            return sum.inv().toInt() and 0xffff
        }

        fun unsignedShort(bytes: ByteArray, offset: Int) =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        fun putShort(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }
    }
}
