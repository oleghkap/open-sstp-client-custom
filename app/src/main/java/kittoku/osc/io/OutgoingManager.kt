package kittoku.osc.io

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.unit.ppp.PPP_HDLC_HEADER
import kittoku.osc.unit.ppp.PPP_PROTOCOL_IP
import kittoku.osc.unit.ppp.PPP_PROTOCOL_IPv6
import kittoku.osc.unit.sstp.SSTP_PACKET_TYPE_DATA
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer


private const val PREFIX_SIZE = 8

private const val IPv4_VERSION_HEADER: Int = (0x4).shl(4 + 3 * Byte.SIZE_BITS)
private const val IPv6_VERSION_HEADER: Int = (0x6).shl(4 + 3 * Byte.SIZE_BITS)
private const val IP_VERSION_MASK: Int = (0xF).shl(4 + 3 * Byte.SIZE_BITS)

private const val TCP_PROTOCOL_NUMBER = 6
private const val TCP_FLAG_SYN = 0x02
private const val TCP_OPTION_KIND_MSS = 2
private const val TCP_OPTION_LENGTH_MSS = 4

// RFC 1624 incremental checksum update: HC' = ~(~HC + ~m + m'), all in 16-bit one's complement.
// Lets us fix up the TCP checksum after rewriting the 16-bit MSS option value, without having
// to re-sum the whole segment.
private fun onesComplementAdd(a: Int, b: Int): Int {
    var sum = a + b
    while (sum shr 16 != 0) {
        sum = (sum and 0xFFFF) + (sum shr 16)
    }
    return sum
}

private fun updatedChecksum(oldChecksum: Int, oldValue: Int, newValue: Int): Int {
    val notOldChecksum = oldChecksum.inv() and 0xFFFF
    val notOldValue = oldValue.inv() and 0xFFFF
    val sum = onesComplementAdd(onesComplementAdd(notOldChecksum, notOldValue), newValue and 0xFFFF)
    return sum.inv() and 0xFFFF
}

private fun getUnsignedByte(packet: ByteBuffer, index: Int): Int = packet.get(index).toInt() and 0xFF
private fun getUnsignedShort(packet: ByteBuffer, index: Int): Int = packet.getShort(index).toInt() and 0xFFFF
private fun putUnsignedShort(packet: ByteBuffer, index: Int, value: Int) {
    packet.putShort(index, (value and 0xFFFF).toShort())
}

// Clamps the TCP MSS option on an outgoing SYN packet so it never advertises more than
// [clamp] bytes, the same technique used by "tunnel MSS clamping" on routers/other VPN clients
// to avoid black-holed connections when the tunnel's own MTU is smaller than the path outside
// it. Only handles the common case (no IP options / no IPv6 extension headers); anything else
// is left untouched rather than risking a malformed packet.
private fun clampTcpMss(packet: ByteBuffer, tcpStart: Int, clamp: Int) {
    if (tcpStart + 20 > packet.limit()) return

    val flags = getUnsignedByte(packet, tcpStart + 13)
    if (flags and TCP_FLAG_SYN == 0) return

    val dataOffsetWords = (getUnsignedByte(packet, tcpStart + 12) shr 4) and 0xF
    val tcpHeaderLength = dataOffsetWords * 4
    if (tcpHeaderLength < 20 || tcpStart + tcpHeaderLength > packet.limit()) return

    var optionOffset = tcpStart + 20
    val optionsEnd = tcpStart + tcpHeaderLength

    while (optionOffset < optionsEnd) {
        val kind = getUnsignedByte(packet, optionOffset)
        if (kind == 0) break // end of options
        if (kind == 1) { optionOffset += 1; continue } // no-op

        if (optionOffset + 1 >= optionsEnd) return
        val length = getUnsignedByte(packet, optionOffset + 1)
        if (length < 2 || optionOffset + length > optionsEnd) return

        if (kind == TCP_OPTION_KIND_MSS && length == TCP_OPTION_LENGTH_MSS) {
            val mssOffset = optionOffset + 2
            val currentMss = getUnsignedShort(packet, mssOffset)

            if (currentMss > clamp) {
                val checksumOffset = tcpStart + 16
                val oldChecksum = getUnsignedShort(packet, checksumOffset)

                putUnsignedShort(packet, mssOffset, clamp)
                putUnsignedShort(packet, checksumOffset, updatedChecksum(oldChecksum, currentMss, clamp))
            }

            return
        }

        optionOffset += length
    }
}

private fun clampIPv4Mss(packet: ByteBuffer, clamp: Int) {
    val ihl = (getUnsignedByte(packet, 0) and 0x0F) * 4
    if (ihl != 20) return // has IP options: skip, too easy to misparse
    if (getUnsignedByte(packet, 9) != TCP_PROTOCOL_NUMBER) return

    clampTcpMss(packet, ihl, clamp)
}

private fun clampIPv6Mss(packet: ByteBuffer, clamp: Int) {
    if (packet.limit() < 40) return
    if (getUnsignedByte(packet, 6) != TCP_PROTOCOL_NUMBER) return // has extension headers: skip

    clampTcpMss(packet, 40, clamp)
}


internal class OutgoingManager(private val bridge: SharedBridge) {
    private var jobMain: Job? = null
    private var jobRetrieve: Job? = null

    private val mainBuffer = ByteBuffer.allocate(bridge.sslTerminal!!.getApplicationBufferSize())
    private val channel = Channel<ByteBuffer>(0)

    internal fun launchJobMain() {
        jobMain = bridge.service.scope.launch(bridge.handler) {
            launchJobRetrieve()

            val minCapacity = PREFIX_SIZE + bridge.PPP_MTU

            while (isActive) {
                mainBuffer.clear()

                if (!load(channel.receive())) continue

                while (isActive) {
                    channel.tryReceive().getOrNull()?.also {
                        load(it)
                    } ?: break

                    if (mainBuffer.remaining() < minCapacity) break
                }

                mainBuffer.flip()
                bridge.sslTerminal!!.send(mainBuffer)
            }
        }
    }

    private fun launchJobRetrieve() {
        jobRetrieve = bridge.service.scope.launch(bridge.handler) {
            val bufferAlpha = ByteBuffer.allocate(bridge.PPP_MTU)
            val bufferBeta = ByteBuffer.allocate(bridge.PPP_MTU)
            var isBlockingAlpha = true

            while (isActive) {
                isBlockingAlpha = if (isBlockingAlpha) {
                    bridge.ipTerminal!!.readPacket(bufferAlpha)
                    channel.send(bufferAlpha)
                    false
                } else {
                    bridge.ipTerminal!!.readPacket(bufferBeta)
                    channel.send(bufferBeta)
                    true
                }
            }
        }
    }

    private suspend fun load(packet: ByteBuffer): Boolean { // true if data protocol is enabled
        val header = packet.getInt(0)
        val protocol = when (header and IP_VERSION_MASK) {
            IPv4_VERSION_HEADER -> {
                if (!bridge.PPP_IPv4_ENABLED) return false

                bridge.MSS_CLAMP_VALUE?.also { clampIPv4Mss(packet, it) }

                PPP_PROTOCOL_IP
            }

            IPv6_VERSION_HEADER -> {
                if (!bridge.PPP_IPv6_ENABLED) return false

                bridge.MSS_CLAMP_VALUE?.also { clampIPv6Mss(packet, it) }

                PPP_PROTOCOL_IPv6
            }

            else -> {
                bridge.controlMailbox.send(ControlMessage(Where.OUTGOING, Result.ERR_UNKNOWN_TYPE))

                return false
            }
        }

        mainBuffer.putShort(SSTP_PACKET_TYPE_DATA)
        mainBuffer.putShort((packet.remaining() + PREFIX_SIZE).toShort())
        mainBuffer.putShort(PPP_HDLC_HEADER)
        mainBuffer.putShort(protocol)
        mainBuffer.put(packet)

        return true
    }

    internal fun cancel() {
        jobMain?.cancel()
        jobRetrieve?.cancel()
        channel.close()
    }
}
