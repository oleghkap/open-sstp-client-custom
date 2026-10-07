package kittoku.osc.control

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.client.SSTP_REQUEST_TIMEOUT
import kittoku.osc.client.SstpClient
import kittoku.osc.client.ppp.IpcpClient
import kittoku.osc.client.ppp.Ipv6cpClient
import kittoku.osc.client.ppp.LCPClient
import kittoku.osc.client.ppp.PPPClient
import kittoku.osc.client.ppp.PPP_NEGOTIATION_TIMEOUT
import kittoku.osc.client.ppp.auth.ChapClient
import kittoku.osc.client.ppp.auth.ChapMSCHAPV2Client
import kittoku.osc.client.ppp.auth.EAPClient
import kittoku.osc.client.ppp.auth.EAPMSAuthClient
import kittoku.osc.client.ppp.auth.PAPClient
import kittoku.osc.debug.assertAlways
import kittoku.osc.io.OutgoingManager
import kittoku.osc.io.incoming.IncomingManager
import kittoku.osc.preference.AUTH_PROTOCOL_EAP_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.resetReconnectionLife
import kittoku.osc.terminal.SSL_REQUEST_INTERVAL
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull


internal class Controller(internal val bridge: SharedBridge) {
    private var observer: NetworkObserver? = null

    private var sstpClient: SstpClient? = null
    private var pppClient: PPPClient? = null
    private var incomingManager: IncomingManager? = null
    private var outgoingManager: OutgoingManager? = null

    private var lcpClient: LCPClient? = null
    private var papClient: PAPClient? = null
    private var chapClient: ChapClient? = null
    private var eapClient: EAPClient? = null
    private var ipcpClient: IpcpClient? = null
    private var ipv6cpClient: Ipv6cpClient? = null

    private var jobMain: Job? = null

    private val mutex = Mutex()

    // RECONNECTION_ENABLED means "limit the number of reconnection attempts". When it's off,
    // reconnection is always available (retries forever until it connects); when on, it's only
    // available while RECONNECTION_LIFE (the remaining-attempts counter) is still positive.
    //
    // This is the ONLY thing that decides whether a broken connection is retried. Auto-connect's
    // explicit rules ("Отключаться при отключении от сетей ...") act separately: they call the normal
    // disconnect action (isReconnectionRequested = false), which always wins regardless of this.
    // A connection that merely breaks because its underlying network changed (e.g. the modem
    // drops mobile data once Wi-Fi takes over) is just a regular failure and is retried exactly
    // like any other one — that is what "ограничивать количество попыток" being off means.
    private val isReconnectionLimited = getBooleanPrefValue(OscPrefKey.RECONNECTION_ENABLED, bridge.prefs)
    private val isReconnectionAvailable: Boolean
        get() = !isReconnectionLimited || getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, bridge.prefs) > 0

    private fun attachHandler() {
        bridge.handler = CoroutineExceptionHandler { _, throwable ->
            kill(isReconnectionRequested = true) {
                val header = "OSC: ERR_UNEXPECTED"
                bridge.service.logWriter?.report(header + "\n" + throwable.stackTraceToString())
                bridge.service.notifyError(header)
            }
        }
    }

    internal fun launchJobMain() {
        attachHandler()

        jobMain = bridge.service.scope.launch(bridge.handler) {
            bridge.attachSSLTerminal()
            bridge.attachIPTerminal()


            bridge.sslTerminal!!.initialize()
            if (!expectProceeded(Where.SSL, SSL_REQUEST_INTERVAL)) {
                return@launch
            }


            IncomingManager(bridge).also {
                it.launchJobMain()
                incomingManager = it
            }


            SstpClient(bridge).also {
                sstpClient = it
                incomingManager!!.registerMailbox(it)
                it.launchJobRequest()

                if (!expectProceeded(Where.SSTP_REQUEST, SSTP_REQUEST_TIMEOUT)) {
                    return@launch
                }

                sstpClient!!.launchJobControl()
            }


            PPPClient(bridge).also {
                pppClient = it
                incomingManager!!.registerMailbox(it)
                it.launchJobControl()
            }


            LCPClient(bridge).also {
                incomingManager!!.registerMailbox(it)
                it.launchJobNegotiation()

                if (!expectProceeded(Where.LCP, PPP_NEGOTIATION_TIMEOUT)) {
                    return@launch
                }

                incomingManager!!.unregisterMailbox(it)
            }


            val authTimeout = getIntPrefValue(OscPrefKey.PPP_AUTH_TIMEOUT, bridge.prefs) * 1000L
            when (bridge.currentAuth) {
                AUTH_PROTOCOl_PAP -> PAPClient(bridge).also {
                    incomingManager!!.registerMailbox(it)
                    it.launchJobAuth()

                    if (!expectProceeded(Where.PAP, authTimeout)) {
                        return@launch
                    }

                    incomingManager!!.unregisterMailbox(it)
                }

                AUTH_PROTOCOL_MSCHAPv2 -> ChapMSCHAPV2Client(bridge).also {
                    chapClient = it
                    incomingManager!!.registerMailbox(it)
                    it.launchJobAuth()

                    if (!expectProceeded(Where.CHAP, authTimeout)) {
                        return@launch
                    }
                }

                AUTH_PROTOCOL_EAP_MSCHAPv2 -> EAPMSAuthClient(bridge).also {
                    eapClient = it
                    incomingManager!!.registerMailbox(it)
                    it.launchJobAuth()

                    if (!expectProceeded(Where.EAP, authTimeout)) {
                        return@launch
                    }
                }

                else -> throw NotImplementedError(bridge.currentAuth)
            }


            sstpClient!!.sendCallConnected()


            if (bridge.PPP_IPv4_ENABLED) {
                IpcpClient(bridge).also {
                    incomingManager!!.registerMailbox(it)
                    it.launchJobNegotiation()

                    if (!expectProceeded(Where.IPCP, PPP_NEGOTIATION_TIMEOUT)) {
                        return@launch
                    }

                    incomingManager!!.unregisterMailbox(it)
                }
            }


            if (bridge.PPP_IPv6_ENABLED) {
                Ipv6cpClient(bridge).also {
                    incomingManager!!.registerMailbox(it)
                    it.launchJobNegotiation()

                    if (!expectProceeded(Where.IPV6CP, PPP_NEGOTIATION_TIMEOUT)) {
                        return@launch
                    }

                    incomingManager!!.unregisterMailbox(it)
                }
            }


            bridge.ipTerminal!!.initialize()
            if (!expectProceeded(Where.IP, null)) {
                return@launch
            }


            OutgoingManager(bridge).also {
                it.launchJobMain()
                outgoingManager = it
            }


            observer = NetworkObserver(bridge)

            // a successful connection refills the remaining-attempts counter, whether or not
            // the limit is currently on (harmless either way; only read when it is)
            resetReconnectionLife(bridge.prefs)


            expectProceeded(Where.SSTP_CONTROL, null) // wait ERR_ message until disconnection
        }
    }

    private suspend fun expectProceeded(where: Where, timeout: Long?): Boolean {
        val received = if (timeout != null) {
            withTimeoutOrNull(timeout) {
                bridge.controlMailbox.receive()
            } ?: ControlMessage(where, Result.ERR_TIMEOUT)
        } else {
            bridge.controlMailbox.receive()
        }

        if (received.result == Result.PROCEEDED) {
            assertAlways(received.from == where)

            return true
        }

        val lastPacketType = if (received.result == Result.ERR_DISCONNECT_REQUESTED) {
            SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
        } else {
            SSTP_MESSAGE_TYPE_CALL_ABORT
        }

        kill(isReconnectionRequested = true) {
            sstpClient?.sendLastPacket(lastPacketType)

            val header = "${received.from.name}: ${received.result.name}"
            var log = header
            if (received.supplement != null) {
                log += "\n${received.supplement}"
            }

            bridge.service.logWriter?.report(log)
            bridge.service.notifyError(header)
        }

        return false
    }

    internal fun disconnect() { // use if the user want to normally disconnect
        kill(false) {
            sstpClient?.sendLastPacket(SSTP_MESSAGE_TYPE_CALL_DISCONNECT)
        }
    }

    // shouldStopService=false is used when a brand new connection attempt is about to start
    // right after this one is torn down (editing settings, switching profiles, saving with the
    // "reconnect" action): the old controller must release its sockets/tun interface, but must
    // NOT call service.close()/stopSelf(), which would otherwise kill the new connection too.
    // The returned Job lets the caller await this cleanup before starting the next attempt.
    //
    // shouldStopService is placed BEFORE cleanup (not after) on purpose: Kotlin binds a
    // trailing lambda `kill(x) { ... }` to the LAST parameter, and every other call site in
    // this file relies on that trailing lambda being `cleanup`.
    internal fun kill(
        isReconnectionRequested: Boolean,
        shouldStopService: Boolean = true,
        cleanup: (suspend () -> Unit)? = null,
    ): Job {
        if (!mutex.tryLock()) return Job().apply { complete() }

        return bridge.service.scope.launch {
            observer?.close()

            jobMain?.cancel()
            cancelClients()

            cleanup?.invoke()

            closeTerminals()

            if (isReconnectionRequested && isReconnectionAvailable) {
                bridge.service.launchJobReconnect()
            } else if (shouldStopService) {
                bridge.service.close()
            }
        }
    }

    private fun cancelClients() {
        lcpClient?.cancel()
        papClient?.cancel()
        chapClient?.cancel()
        eapClient?.cancel()
        ipcpClient?.cancel()
        ipv6cpClient?.cancel()
        sstpClient?.cancel()
        pppClient?.cancel()
        incomingManager?.cancel()
        outgoingManager?.cancel()
    }

    private fun closeTerminals() {
        bridge.sslTerminal?.close()
        bridge.ipTerminal?.close()
    }
}
