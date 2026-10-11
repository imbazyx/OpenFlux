package io.openflux.desktop.diagnostics

import io.openflux.desktop.service.NodeCheckStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every case here is a failure that actually happened, on 2026-10-11, and that
 * took a long time to tell apart. The regression that matters is not a wrong
 * percentage - it is saying "всё в порядке" for a connection that is not.
 */
class NodeDiagnosticsTest {

    @Test
    fun aWholeHealthyRunIsSuccess() {
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] fetchDocInfo POST https://cloud.mail.ru/api/v4/r7/edit",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 0802420534001",
                "[SESSION] send IPv4 #1 seq=1 via \"mailru\" size=64 proto=6",
                "[STATS] uptime=1m0s mode=l4 packets=42 connected=1 established=1 retrans=0",
            )
        )
        assertTrue(r.ok, "должен быть успех, получено: ${r.problem}")
        assertEquals(NodeCheckStage.FirstPacket, r.reached.last())
    }

    @Test
    fun aRefusedUpgradeIsNamedNotCalledASilentNetwork() {
        // The 409 that cost the day: without this sentence the user is told
        // Mail.ru did not answer, which is false and sends them hunting a
        // network problem that cannot be the cause.
        val r = NodeDiagnostics.analyze(listOf("[M-DOCS] WebSocket dial failed (http 409): bad handshake"))
        assertTrue(!r.ok)
        assertNotNull(r.problem)
        assertTrue("409" in r.problem!!, "не назван 409: ${r.problem}")
        assertTrue("устарел" in r.problem!!, "не сказано про устаревшую сборку: ${r.problem}")
    }

    @Test
    fun aWrongKeyIsToldApartFromADeadNode() {
        // Needs the Go fix that logs Auth REJECTED. Without that line this
        // case and the next one are the same empty log.
        val r = NodeDiagnostics.analyze(
            listOf("[M-DOCS] WebSocket connected", "[M-DOCS] Auth REJECTED: access denied"),
            waitedForPacket = true,
        )
        assertTrue(!r.ok)
        assertTrue("Ключ" in r.problem!!, "не назван ключ: ${r.problem}")
        assertEquals(NodeCheckStage.WebSocket, r.reached.last())
    }

    @Test
    fun aMissingNameIsSeparatedFromADocumentThatWillNotOpen() {
        val dns = NodeDiagnostics.analyze(listOf("[M-DOCS] fetchDocInfo failed: dial tcp: lookup cloud.mail.ru: no such host"))
        assertTrue("DNS" in dns.problem!! || "адрес" in dns.problem!!, "не назван DNS: ${dns.problem}")

        val forbidden = NodeDiagnostics.analyze(listOf("[M-DOCS] fetchDocInfo failed: API returned status 403"))
        assertTrue("доступ" in forbidden.problem!!, "не назван доступ: ${forbidden.problem}")

        val missing = NodeDiagnostics.analyze(listOf("[M-DOCS] fetchDocInfo failed: API returned status 404"))
        assertTrue("не найден" in missing.problem!!, "не сказано, что не найдено: ${missing.problem}")
    }

    @Test
    fun authorizedButSilentIsItsOwnSentence() {
        // The Mail.ru docKey rotation state: both ends authorized, nobody in
        // the other's room. Reporting "подключено" here is what left the
        // owner staring at Auth OK and zero traffic for hours.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 0802420534001",
                "[STATS] uptime=6h54m30s mode=l4 packets=0 connected=0 established=0 retrans=0",
            ),
            waitedForPacket = true,
        )
        assertTrue(!r.ok, "Auth OK без пакетов - не успех")
        assertTrue("пакеты не идут" in r.problem!!, "не сказано про отсутствие пакетов: ${r.problem}")
    }

    @Test
    fun tunnelActiveAloneIsNotSuccess() {
        // Printed when the default route moves into the tunnel, before a
        // single packet is exchanged. Counting it as "the node works" is
        // exactly the false all-clear this function exists to remove.
        val r = NodeDiagnostics.analyze(
            listOf("[M-DOCS] WebSocket connected", "[M-DOCS] Auth OK for user 1", "Tunnel active")
        )
        assertTrue(!r.ok, "Tunnel active не равен первому пакету")
    }

    @Test
    fun anEarlyCallerIsNotToldThePacketWillNeverCome() {
        // "No packet yet" and "no packet ever" are different answers; reporting
        // the second from the first turns a slow network into a broken node.
        val r = NodeDiagnostics.analyze(
            listOf("[M-DOCS] WebSocket connected", "[M-DOCS] Auth OK for user 1"),
            waitedForPacket = false,
        )
        assertTrue(!r.ok)
        assertNull(r.problem, "не выдумывать причину, если ждали слишком мало")
    }

    @Test
    fun aLiveSocketProvesTheDocumentAndTheNameToo() {
        // Three of five stages unproven on a working tunnel drew a path with
        // two crosses on it. The document cannot be fetched before the name
        // resolves, and the socket cannot open before the document does.
        val r = NodeDiagnostics.analyze(
            listOf("[M-DOCS] WebSocket connected", "[M-DOCS] Auth OK for user 1", "[STATS] packets=7 established=1")
        )
        assertEquals(
            listOf(
                NodeCheckStage.Document,
                NodeCheckStage.Dns,
                NodeCheckStage.WebSocket,
                NodeCheckStage.Authorize,
                NodeCheckStage.FirstPacket,
            ),
            r.reached,
        )
    }

    @Test
    fun aSuccessfulReconnectBeatsAnOlderFailureInTheBuffer() {
        // The log is a rolling buffer: a 409 from ten minutes ago sits right
        // in front of the good session that followed it. Reading it front to
        // back reports the old failure for a connection that works.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket dial failed (http 409): bad handshake",
                "[M-DOCS] connectToDoc attempt 1",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] packets=910 established=1",
            )
        )
        assertTrue(r.ok, "после успешного переподключения ошибка забыта: ${r.problem}")
    }

    @Test
    fun aReconnectThatFailsAgainKeepsTheStagesAlreadyPassed() {
        // The bug the audit found: a 409 in the LAST attempt was returning
        // reached=empty, because the failure check ran before the proven
        // stages were accumulated. The second attempt did fetch the document -
        // it only failed at the upgrade - so that path must stay on screen.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[M-DOCS] connectToDoc attempt 1",
                "[M-DOCS] WebSocket dial failed (http 409): bad handshake",
            )
        )
        // Not success - the last attempt failed. But the document and the name
        // really were resolved in it, and the problem is the 409.
        assertTrue(!r.ok, "последняя попытка не удалась: ${r.problem}")
        assertEquals(
            listOf(NodeCheckStage.Document, NodeCheckStage.Dns),
            r.reached,
            "стадии до сокета должны остаться зачёркнутыми",
        )
        assertTrue("409" in r.problem!!, "должен быть назван 409: ${r.problem}")
    }

    @Test
    fun theLastAuthVerdictWins() {
        // A session that authorized, then on a later connect had its key
        // refused, is a KEY problem. Reading "one of them exists" sends the
        // user to look at the node instead.
        val r = NodeDiagnostics.analyze(
            listOf("[M-DOCS] WebSocket connected", "[M-DOCS] Auth OK for user 1", "[M-DOCS] Auth REJECTED: denied")
        )
        assertTrue("Ключ" in r.problem!!, "должен быть виноват ключ: ${r.problem}")
    }

    @Test
    fun aSingleStatsLineIsEnoughToProveTraffic() {
        // Per-packet lines need --debug=3 and normally are absent; the 30s
        // STATS line is what a default build actually prints, because the app
        // now starts the core with --debug=2 --no-packets. established>0 is
        // what makes it proof: packets=N alone is cumulative for the whole run.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] uptime=5m0s mode=l4 packets=1183 connected=3 established=2 retrans=0",
            )
        )
        assertTrue(r.ok, "пакеты пошли: ${r.problem}")
        assertEquals(
            listOf(
                NodeCheckStage.Document,
                NodeCheckStage.Dns,
                NodeCheckStage.WebSocket,
                NodeCheckStage.Authorize,
                NodeCheckStage.FirstPacket,
            ),
            r.reached,
        )
    }

    @Test
    fun aPacketCountBeforeAuthorizationBelongsToAnOlderSession() {
        // packets= is cumulative, so a number that moved before this run's
        // "Auth OK" says nothing about it. Counting it drew "первый пакет ✓"
        // over two unchecked steps.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[STATS] uptime=6h54m30s mode=l4 packets=1183 connected=1 established=1 retrans=0",
                "[M-DOCS] Auth OK for user 1",
            )
        )
        assertEquals(
            listOf(NodeCheckStage.Document, NodeCheckStage.Dns, NodeCheckStage.WebSocket, NodeCheckStage.Authorize),
            r.reached,
            "стадии должны идти по порядку",
        )
    }

    @Test
    fun aSendLineIsNotTraffic() {
        // session.go:677 logs the send BEFORE the carrier is asked to carry it,
        // so a carrier that refuses everything still prints one.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[SESSION] send IPv4 #1 seq=1 via \"mailru\" size=64 proto=6",
            ),
            waitedForPacket = true,
        )
        assertTrue(!r.ok, "отправленный пакет ещё не дошёл")
    }

    @Test
    fun oneCarrierGivingUpIsNotAFaultWhileTrafficMoves() {
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[YDOCS] giving up after 5 reconnect attempts, max is 5",
                "[STATS] uptime=6h54m30s mode=l4 packets=910 connected=1 established=1 retrans=0",
            )
        )
        assertTrue(r.ok, "второй транспорт отказался, первый несёт трафик: ${r.problem}")
    }

    @Test
    fun aDeadCoreIsNotTrafficAnyMore() {
        val r = NodeDiagnostics.analyze(
            listOf(
                "Запуск ядра: Nodes (L4 клиент)",
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] uptime=6h54m30s mode=l4 packets=910 connected=1 established=1 retrans=0",
                "Ядро остановилось (код 2)",
            ),
            waitedForPacket = true,
        )
        assertTrue(!r.ok, "ядро мертво, а счётчики старые")
        assertTrue("остановилось" in r.problem!!, "должно быть сказано про остановку: ${r.problem}")
    }

    @Test
    fun aDeadCarrierDoesNotSpeakOverALiveOne() {
        // Yandex died an hour ago and left a 404 in the buffer; Mail.ru has
        // been carrying traffic since. Cutting at the earliest of the two
        // attempts keeps the Yandex history visible - and its failure must not
        // be the answer for a tunnel that works.
        val r = NodeDiagnostics.analyze(
            listOf(
                "Запуск ядра: Nodes (L4 клиент)",
                "[YDOCS] connectToDoc attempt 0",
                "[YDOCS] fetchDocInfo failed: API returned status 404",
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] uptime=6h54m30s mode=l4 packets=910 connected=1 established=1 retrans=0",
            )
        )
        assertTrue(r.ok, "живой транспорт должен победить мёртвый: ${r.problem}")
    }

    @Test
    fun aVolgaProfileIsNotStuckAtTheAuthorizationStep() {
        // Volga and Boards print their own wording; a check that reads only
        // Mail.ru's leaves the newest transports permanently at step three.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[VOLGA] WS connected",
                "[VOLGA] auth OK: user=1(abc)",
                "[STATS] packets=910 established=1",
            )
        )
        assertEquals(
            listOf(
                NodeCheckStage.Document,
                NodeCheckStage.Dns,
                NodeCheckStage.WebSocket,
                NodeCheckStage.Authorize,
                NodeCheckStage.FirstPacket,
            ),
            r.reached,
        )
        assertTrue(r.ok, "пакеты пошли: ${r.problem}")
    }

    @Test
    fun twoCarriersKeepEachOthersEvidence() {
        // A session drives both transports. Cutting at whichever spoke last
        // erased the other one's whole run and reported a carrying node as
        // "nothing established".
        val r = NodeDiagnostics.analyze(
            listOf(
                "[YDOCS] connectToDoc attempt 0",
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] packets=910 established=1",
                "[M-DOCS] connectToDoc attempt 1",
            )
        )
        assertTrue(r.ok, "активный транспорт нёс трафик: ${r.problem}")
    }

    @Test
    fun anOldPacketCountIsNotTrafficNow() {
        // packets= is cumulative and bumped BEFORE the send (tunnel.go:130), so
        // after a carrier drop it still reads 1183. Calling that "связь есть"
        // is the one answer this function must never give.
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] uptime=6h54m30s mode=l4 packets=1183 connected=0 established=0 retrans=0",
            )
        )
        assertTrue(!r.ok, "накопленный счётчик не доказывает текущую связь")
    }

    @Test
    fun trafficAfterARefusedReconnectBeatsTheOldSession() {
        val r = NodeDiagnostics.analyze(
            listOf(
                "Запуск ядра: Nodes (L4 клиент)",
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] uptime=6h54m30s mode=l4 packets=910 connected=1 established=1 retrans=0",
            )
        )
        assertTrue(r.ok, "работающая сессия должна читаться как связь есть")
    }

    @Test
    fun theLogOfTheSessionBeforeIsNotThisSession() {
        // The buffer is never cleared when the core stops, so a perfect run
        // from yesterday sits in front of a profile that cannot connect at all.
        // Answering from it is the false all-clear this card exists to remove.
        val r = NodeDiagnostics.analyze(
            listOf(
                "Запуск ядра: Nodes (L4 клиент)",
                "[M-DOCS] connectToDoc attempt 0",
                "[M-DOCS] WebSocket connected",
                "[M-DOCS] Auth OK for user 1",
                "[STATS] uptime=6h54m30s mode=l4 packets=910 connected=1 established=1 retrans=0",
                "[SESSION] Ядро остановилось",
                "Запуск ядра: Nodes (L4 клиент)",
            ),
            waitedForPacket = true,
        )
        assertTrue(!r.ok, "прошлая сессия не должна выдаваться за текущую")
        assertEquals(emptyList(), r.reached)
    }

    @Test
    fun aCoreThatGaveUpSaysSoWithItsOwnSentence() {
        // mailru.go:843 / yandex.go:470. Below this line nothing the user does
        // to the network helps, and the lines above it read as "one more try".
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] giving up after 5 reconnect attempts, max is 5; the tunnel is down and will not recover on its own",
            )
        )
        // It names the carrier that gave up, and the button that fixes it: the
        // sentence used to say "перезапустите подключение", which is not a thing
        // on screen - nobody presses "перезапустить".
        assertTrue("Mail.ru прекратил попытки" in r.problem!!, "нужен свой текст: ${r.problem}")
        assertTrue("Отключить" in r.problem!!, "нужно сказать, что нажать: ${r.problem}")
    }

    @Test
    fun theVendorIsTheOneInTheLog() {
        val y = NodeDiagnostics.analyze(listOf("[YDOCS] WebSocket dial failed (http 409): bad handshake"))
        assertTrue("Яндекс" in y.problem!!, "профиль Яндекса не должен быть назван Mail.ru: ${y.problem}")
        val m = NodeDiagnostics.analyze(listOf("[M-DOCS] WebSocket dial failed (http 409): bad handshake"))
        assertTrue("Mail.ru" in m.problem!!, "профиль Mail.ru должен быть назван верно: ${m.problem}")
    }

    @Test
    fun anEmptyLogIsNotSuccess() {
        val r = NodeDiagnostics.analyze(emptyList(), waitedForPacket = true)
        assertTrue(!r.ok, "пустой лог - это не работающее подключение")
        assertNotNull(r.problem)
    }

    @Test
    fun theReachedListNeverSkipsAStepItCannotProve() {
        // Document and DNS are inferred from the absence of a later failure;
        // claiming them from a single line would report progress never made.
        val r = NodeDiagnostics.analyze(listOf("[M-DOCS] WebSocket dial failed (http 409): bad handshake"))
        assertTrue(r.reached.isEmpty(), "не доказан ни один шаг, а заявлено: ${r.reached}")
    }

    @Test
    fun aFirstFailureWins() {
        // The document failed AND the socket died later. The first one is the
        // cause; the second is a consequence of reading it as "not connected".
        val r = NodeDiagnostics.analyze(
            listOf(
                "[M-DOCS] fetchDocInfo failed: no such host",
                "[M-DOCS] WebSocket dial failed (http 0): connection refused",
            )
        )
        assertTrue("адрес" in r.problem!!, "должна быть причина из первой строки: ${r.problem}")
    }
}