package io.openflux.desktop.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The core announces readiness differently depending on the inbound, and the
 * window never leaves "Подключение" if we only listen to one of them.
 *
 * The lines are taken verbatim from a real 2.3.0 run over Wintun: that build
 * sat on "Подключение" for as long as anyone cared to look, while 2ip.ua
 * already showed the node's address. The tunnel was up; nothing had noticed.
 */
class ReadyLineTest {

    @Test
    fun `the tun path announces readiness with Tunnel active`() {
        assertTrue(announcesReady("2026/10/01 08:29:35 Tunnel active"))
    }

    @Test
    fun `the legacy socks path still counts`() {
        assertTrue(announcesReady("Running as CLIENT (SOCKS5 on 127.0.0.1:1080, legacy gVisor path)"))
    }

    @Test
    fun `an exit node counts too`() {
        assertTrue(announcesReady("Running as EXIT NODE (mode=full)"))
    }

    @Test
    fun `startup chatter is not readiness`() {
        val chatter = listOf(
            "=== OpenFlux ===",
            "Role: client",
            "Transport: mailru",
            "Inbound: tun",
            "Codec: batched (zstd + coalescing)",
            "Using existing driver 0.14",
            "Creating adapter",
            "utun interface: OpenFlux (интерфейс 31)",
            "utun up; bypass gateway is интерфейс 2",
            "Socket set stable; taking default route into the tunnel",
            "core sockets bound to interface 2",
        )
        for (line in chatter) {
            assertFalse(announcesReady(line), "принято за готовность: $line")
        }
    }

    @Test
    fun `a failure is never readiness`() {
        assertFalse(announcesReady("FATAL: configure default: cannot open adapter"))
        assertFalse(announcesReady("Shutting down, restoring default route..."))
    }
}
