package io.openflux.desktop.diagnostics

import io.openflux.desktop.service.NodeCheckResult
import io.openflux.desktop.service.NodeCheckStage

/**
 * Turns the core's own log into "how far did it get".
 *
 * It reads what the core actually printed rather than running a second,
 * simpler copy of the same network calls. A parallel implementation would be
 * one more thing to disagree with the real one, and would report success for
 * a document the real client cannot open - which is precisely the case this
 * exists to explain.
 *
 * The stage order is the connection's order, so the first stage with no
 * evidence after it is where it stopped.
 */
object NodeDiagnostics {

    private val ATTEMPT_START = Regex("""connectToDoc attempt""")
    private val STATS_PACKETS = Regex("""packets=(\d+)""")
    private val STATS_ESTABLISHED = Regex("""established=(\d+)""")

    /** Both clients log this when the core starts (see analyze). */
    private const val SESSION_START = "Запуск ядра:"

    /**
     * The run is over, and by what the client says rather than the core: the
     * core is not a process this code watches, so its death is reported here.
     * JVM: CoreConnectionService.kt:334. Android never says "ядро" - it names
     * what it was doing instead (AndroidConnectionService.kt:339/354/415/428/
     * 485), and each of those is a run that will not recover on its own.
     */
    private val CORE_GONE_MARKERS = listOf(
        "Ядро остановилось",
        "Ядро не запустилось",
        "Прокси остановился",
        "Android остановил службу OpenFlux",
        "Android не создал VPN-интерфейс",
        "VPN выключен:",
        "Транспорт не подключился за",
        // Deliberately NOT "WS disconnected" / "ws error": every reconnect of
        // a flappy session prints them, and calling that "остановилось" would
        // send the user to break a tunnel that recovers on its own. The dead
        // carrier of an otherwise quiet run is still caught by "giving up".
    )

    /** The user switched the connection off; nothing is wrong. */
    private const val TURNED_OFF = "Отключено"

    // Both sentences name the button that exists on screen. "Включите ноду"
    // contradicted itself: a node is the remote exit, and nobody in the app
    // presses a button with that name.
    private const val CORE_GONE = "Подключение остановилось. Нажмите «Повторить» на главном экране"
    private const val OFF = "Подключение выключено. Нажмите «Подключить» и проверьте ещё раз"

    /** Log prefixes that identify the carrier, and so the service being named. */
    private val CARRIERS = listOf("[M-DOCS]", "[YDOCS]")

    /**
     * Carriers whose log prefix is not in [CARRIERS] but which authorize the
     * document before opening a socket.
     */
    private val SOCKETLESS = listOf("[VOLGA]", "[BOARDS]")

    private fun carrierIsSocketless(line: String) = SOCKETLESS.any { line.contains(it) }

    /**
     * "The document connection is open".
     *
     * Mail.ru says "WebSocket connected" (mailru.go:285), Yandex the same
     * (yandex.go:242), Volga and Boards their own wording (vyandex.go:1017,
     * boards.go:532). One shared wording would leave the two newest
     * transports permanently stuck at the authorization step.
     */
    private fun socketUp(line: String) =
        line.contains("WebSocket connected") || line.contains("WS connected")

    /** "The key was accepted", in every spelling the core uses. */
    private fun authorizedLine(line: String) =
        line.contains("Auth OK") || line.contains("auth OK")

    /**
     * The core stopped retrying on its own (mailru.go:843, yandex.go:470).
     *
     * Its own sentence, because from here nothing the user does to the network
     * helps: only a restarted core does, and the lines above it read as "one
     * more attempt". It names the TRANSPORT, not the core - one carrier giving
     * up is what a session is for, the others carry on.
     */
    private const val GAVE_UP =
        "%s прекратил попытки и сам не восстановится. Если другие адреса тоже молчат — нажмите «Отключить», потом «Подключить»"

    /**
     * [lines] from the core's log, oldest first.
     *
     * [waitedForPacket] says the caller had time to see traffic rather than
     * giving up early: "no packet yet" and "no packet ever" are different
     * answers, and reporting the second from the first is how a slow network
     * gets reported as a broken node. False by default because the caller
     * usually pressed the button without waiting for the 30-second cycle.
     */
    fun analyze(lines: List<String>, waitedForPacket: Boolean = false): NodeCheckResult {
        val reached = mutableListOf<NodeCheckStage>()

        // Analyze only the LAST attempt of the LAST run.
        //
        // The log is a rolling buffer of every attempt the core has ever made,
        // and reading it whole reports an outcome that no longer matches what
        // is happening: a 409 from an earlier attempt that later succeeded was
        // reported as broken, because the old 409 was still in the buffer.
        // Both transports mark an attempt with "connectToDoc attempt N"
        // (mailru.go:224, yandex.go:166), and both clients mark the start of a
        // run with "Запуск ядра:" (CoreConnectionService.kt:218,
        // AndroidConnectionService.kt:218). Without the second, a stopped core
        // is judged by the log of the session that ran before it - which can be
        // a perfect run.
        //
        // Per carrier, and the EARLIEST of them: a session profile drives two
        // transports at once, and cutting at the last attempt of whichever
        // spoke last erased the other carrier's whole run - a node carrying
        // traffic was reported as "nothing established".
        //
        // Only the carriers that actually print that marker take part. Volga and
        // Boards do not, and adding them to the list made them worse: with no
        // marker of their own they fell back to the OTHER carrier's cut, so a
        // Mail.ru reconnect at the end of the buffer erased an authorized Volga
        // run that had not yet carried a byte.
        val runStart = lines.indexOfLast { it.contains(SESSION_START) }
        // indexOfLast answers -1 for a carrier that never spoke, and mapNotNull
        // does not drop an -1: min() would then be -1 and the cut would
        // silently disappear for every single-carrier profile.
        val lastAttemptOf = mutableMapOf<String, Int>()
        for (prefix in CARRIERS) {
            val at = lines.indexOfLast { it.contains(prefix) && ATTEMPT_START.containsMatchIn(it) }
            if (at >= 0) lastAttemptOf[prefix] = at
        }
        val anchored = maxOf(runStart, lastAttemptOf.values.minOrNull() ?: -1)

        /**
         * Whether [index] is still inside the current attempt of its OWN
         * carrier. Carried separately from the cut above because that one
         * deliberately keeps another carrier's history, and a 404 from a
         * transport that died an hour ago would then be reported over a
         * Mail.ru tunnel that is carrying 910 packets right now.
         */
        fun current(index: Int, line: String): Boolean {
            val own = CARRIERS.firstOrNull { line.contains(it) }?.let { lastAttemptOf[it] }
            return index >= (own ?: anchored)
        }
        val tail = if (anchored >= 0) lines.drop(anchored) else lines

        // Everything that still belongs to a live attempt, in order. The tail may
        // carry another carrier's history on purpose (see the cut above), so
        // lines older than their own carrier's last attempt are dropped here -
        // a 404 from a transport that died an hour ago must not be reported
        // over a Mail.ru tunnel that is carrying packets right now.
        val fresh = tail.withIndex()
            .filter { (offset, line) -> current(maxOf(anchored, 0) + offset, line) }
            .map { it.value }

        // Stages are accumulated as the tail is walked, and the walk CONTINUES
        // past the first failure on purpose: in a session profile the second
        // carrier is often the one that works, and stopping at the first failure
        // drew an empty path under a working half. The FIRST failure is kept -
        // what stopped this attempt is the first thing that went wrong - and it
        // is judged only after the traffic, because one carrier's dead document
        // is not the answer while another carrier is carrying packets.
        var failure: String? = null
        var failureLine: String? = null
        var gaveUpBy: String? = null
        for (line in fresh) {
            when {
                documentFailed(line) -> if (failure == null) {
                    failure = documentProblem(line)
                    failureLine = line
                }
                // Not a failure on its own: one transport giving up is what a
                // session is FOR - the other one carries on. The LAST one is
                // kept: it is the carrier the user is looking at now.
                gaveUp(line) -> gaveUpBy = line
                dialFailed(line) -> {
                    // The socket URL only exists after the document opened,
                    // which only happens after its name resolved. Inferred from
                    // the failed upgrade, so it needs the marker to be a whole
                    // attempt and not a stray line out of the buffer.
                    if (anchored >= 0) reached.upTo(NodeCheckStage.Dns)
                    if (failure == null) {
                        failure = dialProblem(line)
                        failureLine = line
                    }
                }
                // A live socket proves the two stages before it, the same way.
                // Volga and Boards print their own wording (vyandex.go:1017,
                // boards.go:532), so this matches the words and not one
                // transport's spelling of them.
                socketUp(line) -> reached.upTo(NodeCheckStage.WebSocket)
                authorizedLine(line) -> if (carrierIsSocketless(line)) {
                    // Volga and Boards fetch and authorize the DOCUMENT before
                    // they open any socket (vyandex.go:331-417, boards.go:157),
                    // so their "auth OK" proves everything up to the key and
                    // nothing after it. Back-filling the WebSocket stage from it
                    // ticked a stage that never happened; ticking nothing at all
                    // drew "the document was never opened" on the very line that
                    // accepted the key.
                    reached.add(NodeCheckStage.Document)
                    reached.add(NodeCheckStage.Dns)
                    reached.add(NodeCheckStage.Authorize)
                } else {
                    reached.upTo(NodeCheckStage.Authorize)
                }
                // Counted only after the authorization of THIS run: the counter
                // is cumulative, so a packet seen before the authorization
                // belongs to a previous session and says nothing about this one.
                // Without that condition the drawn path had "первый пакет ✓"
                // sitting on top of two unchecked steps.
                packetMoved(line) && NodeCheckStage.Authorize in reached ->
                    reached.add(NodeCheckStage.FirstPacket)
            }
        }

        val authorized = NodeCheckStage.Authorize in reached

        // The run itself is over: everything above it was printed by a process
        // that is no longer running, so its traffic counters prove nothing -
        // checked BEFORE the traffic for that reason.
        //
        // The start line is excluded from both tests. It is
        // "Запуск ядра: ${profile.name}" (CoreConnectionService.kt:218,
        // AndroidConnectionService.kt:218), and the name is the user's own text:
        // a profile called "Отключено" or "Ядро остановилось" turned this into a
        // permanent "подключение выключено" for a node carrying traffic.
        val events = fresh.filterNot { it.contains(SESSION_START) }
        if (events.any { it.contains(TURNED_OFF) }) return stopped(reached, OFF)
        if (events.any { line -> CORE_GONE_MARKERS.any { line.contains(it) } }) {
            return stopped(reached, CORE_GONE)
        }

        // Traffic outranks a stale key complaint: packets are moving right now,
        // so whatever the log said about the key describes an attempt that is
        // no longer the one in force. Reversed once, this sent the owner to
        // re-copy a key while the tunnel was carrying data.
        if (fresh.any { packetMoved(it) }) {
            return NodeCheckResult(path(reached), ok = true, problem = null)
        }

        // Judged after the traffic, for the same reason: a document that one
        // carrier cannot open is not the verdict while another one carries the
        // session - or while another one has already accepted the key. Reporting
        // "Mail.ru не пускает" over a Yandex half that authorized sent the user
        // to fix the wrong document.
        val failedCarrier = failureLine?.let { line -> CARRIERS.firstOrNull { line.contains(it) } }
        val anotherCarrierAuthorized = failedCarrier != null &&
            fresh.any { authorizedLine(it) && !it.contains(failedCarrier) }
        if (failure != null && !anotherCarrierAuthorized) return stopped(reached, failure)
        // The LAST carrier to give up, not the first: the one that spoke last is
        // the one the user is looking at.
        if (gaveUpBy != null) return stopped(reached, GAVE_UP.replace("%s", vendorOf(gaveUpBy)))

        // The LAST auth verdict, not "one of them exists": a session that
        // authorized and then, on a later connect, had its key refused reads
        // as authorized otherwise, and the user is told to look at the node
        // when the key is what they must change.
        val lastAuthOk = fresh.indexOfLast { authorizedLine(it) }
        val lastAuthRejected = fresh.indexOfLast { it.contains("Auth REJECTED") }
        if (lastAuthRejected > lastAuthOk) {
            return stopped(
                reached,
                "Ключ не подходит к этому документу. Ключ и адрес должны быть из одной пары — той, что у ноды.",
            )
        }

        // Nothing in the log explains it. "No packet yet" and "no packet ever"
        // are different answers, and reporting the second from the first turns a
        // slow network into a broken node. The caller knows how long it waited.
        if (!waitedForPacket) {
            return NodeCheckResult(path(reached), ok = false, problem = null)
        }
        // Auth OK with no traffic is a real and confusing state, not an error
        // to shrug at: Mail.ru can rotate the document key, leaving both ends
        // authorized and in different rooms. It has a distinct sentence because
        // "всё хорошо, но пакетов нет" is what the owner saw for hours.
        if (authorized) {
            return stopped(
                reached,
                "Авторизация прошла, но пакеты не идут. Обычно нода ещё не запущена или в документе уже есть другой клиент.",
            )
        }
        return NodeCheckResult(
            path(reached),
            ok = false,
            problem = "Подключение не установилось за отведённое время. Проверьте, что нода включена, и попробуйте ещё раз",
        )
    }

    private fun stopped(reached: List<NodeCheckStage>, why: String) =
        NodeCheckResult(path(reached), ok = false, problem = why)

    /** The path in stage order, with a stage that arrived twice kept once. */
    private fun path(reached: List<NodeCheckStage>): List<NodeCheckStage> {
        val order = NodeCheckStage.entries
        return reached.distinct().sortedBy { order.indexOf(it) }
    }

    /**
     * Append the missing tail of the path up to [upTo].
     *
     * The stages only ever happen in order, so a stage that was proven proves
     * the ones before it. Filling the gap instead of adding one name keeps the
     * drawn path free of holes like "document ✓, WebSocket ✓, DNS ?".
     */
    private fun MutableList<NodeCheckStage>.upTo(upTo: NodeCheckStage) {
        val order = NodeCheckStage.entries
        for (stage in order.take(order.indexOf(upTo) + 1)) {
            if (stage !in this) add(stage)
        }
    }

    /**
     * Traffic actually crossing the tunnel.
     *
     * "Tunnel active" is deliberately not one of these: the core prints it once
     * the default route is pointed into the tunnel, before a single packet has
     * been exchanged, and treating it as success is how a broken node gets
     * reported as a working one. Neither is "send IPv4": the core logs that
     * line BEFORE it hands the packet to the carrier (session.go:677 against
     * :679), so a carrier that refuses everything still produces one.
     *
     * "recv IPv4" is data that came back through the tunnel, which a dead
     * carrier cannot fake.
     *
     * The counters are read together with established=N, never on their own:
     * packets=N is cumulative for the whole run and is bumped BEFORE the send
     * (tunnel.go:130), so after a failed reconnect the number still says "1183"
     * and a plain reading of it answers "связь есть" for a dead tunnel.
     *
     * ponytail: established counts the client's own TCP sessions, not carrier
     * liveness, so a node that dropped and is inside the ~3-minute watchdog
     * still reads as working. Cut at "reconnect scheduled" (mailru.go:853) when
     * that false "связь есть" actually reaches a user.
     */
    private fun packetMoved(line: String): Boolean =
        line.contains("recv IPv4") ||
            ((STATS_PACKETS.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) > 0L &&
                (STATS_ESTABLISHED.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) > 0L)

    /** Which service a log line is about: a Yandex profile must not be told Mail.ru refused it. */
    private fun vendorOf(line: String) = when {
        line.contains("[YDOCS]") || line.contains("[VOLGA]") || line.contains("[BOARDS]") -> "Яндекс"
        else -> "Mail.ru"
    }

    /** The core gave up retrying: nothing below this line will ever change. */
    private fun gaveUp(line: String) = line.contains("giving up after")

    /**
     * The document stage: a malformed URL or one the server will not open.
     *
     * "keeping session" is the exception that matters: the key recheck every
     * five minutes (mailru.go:723) prints the raw error of a failed re-fetch
     * and deliberately does NOT tear the session down. Reading that as a failed
     * document told a connected, authorized node that it could not open its
     * document - every five minutes, forever.
     */
    private fun documentFailed(line: String): Boolean {
        if (line.contains("keeping session")) return false
        return line.contains("fetchDocInfo failed") ||
            line.contains("document object missing") ||
            line.contains("API returned status")
    }

    private fun documentProblem(line: String): String {
        val vendor = vendorOf(line)
        if (line.contains("no such host")) {
            return "Не удалось открыть адрес в интернете: нет связи с сетью или сломан DNS"
        }
        if (line.contains("API returned status 403")) {
            return "Документ не пускает это подключение. Откройте документ в $vendor и включите доступ «Редактирование по ссылке»"
        }
        if (line.contains("API returned status 404")) {
            return "Документ не найден. Проверьте адрес"
        }
        if (line.contains("connection refused") || line.contains("timeout")) {
            return "$vendor не отвечает. Проверьте связь с интернетом"
        }
        // No raw core text here: it is English and half machine-readable, and a
        // Russian sentence with "API returned status 500" in it reads as noise.
        return "Не удалось открыть документ. Подробности в журнале ядра"
    }

    private fun dialFailed(line: String) =
        line.contains("WebSocket dial failed")

    /**
     * The refused upgrade, named exactly.
     *
     * 409 has its own sentence because it is not "сервис не отвечает": it is
     * this specific build talking to the edge in a way the edge refuses, and
     * naming it saves the reader from a network hunt that cannot succeed.
     */
    private fun dialProblem(line: String): String {
        val vendor = vendorOf(line)
        if (line.contains("http 409")) {
            return "$vendor отклонил подключение (409). Эта сборка устарела — обновите приложение"
        }
        if (line.contains("http 403") || line.contains("http 401")) {
            return "Документ не пускает это подключение. Откройте документ в $vendor и включите доступ «Редактирование по ссылке»"
        }
        if (line.contains("no such host")) {
            return "Не удалось открыть адрес в интернете: нет связи с сетью или сломан DNS"
        }
        if (line.contains("timeout") || line.contains("deadline")) {
            return "$vendor не отвечает вовремя. Проверьте связь с интернетом"
        }
        return "Не удалось соединиться с документом. Подробности в журнале ядра"
    }
}