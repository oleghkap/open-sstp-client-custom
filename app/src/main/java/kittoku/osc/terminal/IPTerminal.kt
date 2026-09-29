package kittoku.osc.terminal

import android.os.ParcelFileDescriptor
import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.extension.toHexByteArray
import kittoku.osc.preference.LIST_TYPE_ALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong


// a normalized IPv4 CIDR block: `network` already masked down to `prefix` bits
private data class Cidr(val network: Long, val prefix: Int)

private fun ipv4ToLong(ip: String): Long? {
    val parts = ip.split(".")
    if (parts.size != 4) return null

    var result = 0L
    for (part in parts) {
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        result = (result shl 8) or value.toLong()
    }

    return result
}

private fun longToIpv4(value: Long): String {
    return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
}

private fun networkOf(address: Long, prefix: Int): Long {
    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    return address and mask
}

// returns the CIDR blocks that cover `base` minus `exclude` (both normalized).
// Android's VpnService only supports adding routes into the tunnel, never excluding a
// sub-range from one already added, so an excluded subnet is carved out by replacing the
// single covering block with the smaller set of blocks that tile everything around the hole.
private fun subtractCidr(base: Cidr, exclude: Cidr): List<Cidr> {
    if (exclude.prefix <= base.prefix) {
        // exclude is the same size or broader than base: either it swallows base whole,
        // or it doesn't touch base at all
        return if (networkOf(base.network, exclude.prefix) == exclude.network) emptyList() else listOf(base)
    }

    if (networkOf(exclude.network, base.prefix) != base.network) {
        return listOf(base) // exclude falls outside base entirely
    }

    val pieces = mutableListOf<Cidr>()
    var currentPrefix = base.prefix
    var currentBase = base.network

    while (currentPrefix < exclude.prefix) {
        val half = 1L shl (32 - currentPrefix - 1)
        val lower = currentBase
        val upper = currentBase + half

        if (networkOf(exclude.network, currentPrefix + 1) == upper) {
            pieces.add(Cidr(lower, currentPrefix + 1))
            currentBase = upper
        } else {
            pieces.add(Cidr(upper, currentPrefix + 1))
            currentBase = lower
        }

        currentPrefix += 1
    }

    return pieces
}

private fun subtractAll(base: Cidr, excludes: List<Cidr>): List<Cidr> {
    var pieces = listOf(base)
    excludes.forEach { exclude -> pieces = pieces.flatMap { subtractCidr(it, exclude) } }
    return pieces
}


internal class IPTerminal(private val bridge: SharedBridge) {
    private var fd: ParcelFileDescriptor? = null

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    // live traffic counters for the notification/UI. "In" = bytes delivered to apps on this
    // device (written into the tun device, i.e. arriving from the server); "Out" = bytes read
    // from the tun device (originating from apps on this device, heading to the server).
    internal val bytesIn = AtomicLong(0)
    internal val bytesOut = AtomicLong(0)

    private val doEnableAppBasedRule = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, bridge.prefs)
    private val isAllowedList = getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, bridge.prefs) == LIST_TYPE_ALLOWED
    private val doAddDefaultRoute = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE, bridge.prefs)
    private val doRoutePrivateAddresses = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ROUTE_PRIVATE_ADDRESSES, bridge.prefs)
    private val doUseCustomDNSServer = getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, bridge.prefs)
    private val doAddCustomRoutes = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_CUSTOM_ROUTES, bridge.prefs)

    internal suspend fun initialize() {
        // parse custom routes up front so that lines starting with "!" (exclusions) can also
        // carve a hole out of the default route / private-address routes below, not just out
        // of other custom routes
        var customPositives = emptyList<Pair<String, Int>>()
        var customExcludes = emptyList<Cidr>()

        if (doAddCustomRoutes) {
            val resolved = resolveCustomRoutes() ?: return
            customPositives = resolved.first
            customExcludes = resolved.second
        }

        if (bridge.PPP_IPv4_ENABLED) {
            if (bridge.currentIPv4.contentEquals(ByteArray(4))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
                return
            }

            InetAddress.getByAddress(bridge.currentIPv4).also {
                bridge.builder.addAddress(it, 32)
            }

            if (doUseCustomDNSServer) {
                bridge.builder.addDnsServer(getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, bridge.prefs))
            }

            if (!bridge.currentProposedDNS.contentEquals(ByteArray(4))) {
                InetAddress.getByAddress(bridge.currentProposedDNS).also {
                    bridge.builder.addDnsServer(it)
                }
            }

            setIPv4BasedRouting(customExcludes)
        }

        if (bridge.PPP_IPv6_ENABLED) {
            if (bridge.currentIPv6.contentEquals(ByteArray(8))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv6, Result.ERR_INVALID_ADDRESS))
                return
            }

            ByteArray(16).also { // for link local addresses
                "FE80".toHexByteArray().copyInto(it)
                ByteArray(6).copyInto(it, destinationOffset = 2)
                bridge.currentIPv6.copyInto(it, destinationOffset = 8)
                bridge.builder.addAddress(InetAddress.getByAddress(it), 64)
            }

            setIPv6BasedRouting()
        }

        if (doAddCustomRoutes) {
            addPositiveCustomRoutes(customPositives, customExcludes)
        }

        if (doEnableAppBasedRule) {
            addAppBasedRules()
        }

        bridge.builder.setMtu(bridge.PPP_MTU)
        bridge.builder.setBlocking(true)

        fd = bridge.builder.establish()!!.also {
            inputStream = FileInputStream(it.fileDescriptor)
            outputStream = FileOutputStream(it.fileDescriptor)
        }

        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    private fun addIPv4RouteExcluding(base: Cidr, excludes: List<Cidr>) {
        subtractAll(base, excludes).forEach {
            bridge.builder.addRoute(longToIpv4(it.network), it.prefix)
        }
    }

    private fun setIPv4BasedRouting(excludes: List<Cidr>) {
        if (doAddDefaultRoute) {
            addIPv4RouteExcluding(Cidr(0L, 0), excludes)
        }

        if (doRoutePrivateAddresses) {
            addIPv4RouteExcluding(Cidr(networkOf(ipv4ToLong("10.0.0.0")!!, 8), 8), excludes)
            addIPv4RouteExcluding(Cidr(networkOf(ipv4ToLong("172.16.0.0")!!, 12), 12), excludes)
            addIPv4RouteExcluding(Cidr(networkOf(ipv4ToLong("192.168.0.0")!!, 16), 16), excludes)
        }
    }

    private fun setIPv6BasedRouting() {
        if (doAddDefaultRoute) {
            bridge.builder.addRoute("::", 0)
        }

        if (doRoutePrivateAddresses) {
            bridge.builder.addRoute("fc00::", 7)
        }
    }

    private fun addAppBasedRules() {
        bridge.selectedApps.forEach {
            if (isAllowedList) {
                bridge.builder.addAllowedApplication(it.packageName)
            } else {
                bridge.builder.addDisallowedApplication(it.packageName)
            }
        }
    }

    // parses ROUTE_CUSTOM_ROUTES into (positive "address/prefix" entries, excluded IPv4 CIDRs).
    // A line starting with "!" is an exclusion, e.g. "!192.168.1.0/24" keeps that subnet out of
    // the tunnel even while the default route sends everything else through it. Exclusions are
    // only supported for IPv4 (the common case: excluding a local subnet from a 0.0.0.0/0 route).
    private suspend fun resolveCustomRoutes(): Pair<List<Pair<String, Int>>, List<Cidr>>? {
        val positives = mutableListOf<Pair<String, Int>>()
        val excludes = mutableListOf<Cidr>()

        getStringPrefValue(OscPrefKey.ROUTE_CUSTOM_ROUTES, bridge.prefs).split("\n").filter { it.isNotEmpty() }.forEach { rawLine ->
            val isExclude = rawLine.startsWith("!")
            val line = if (isExclude) rawLine.substring(1) else rawLine

            val parsed = line.split("/")
            if (parsed.size != 2) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return null
            }

            val address = parsed[0]
            val prefix = parsed[1].toIntOrNull()
            if (prefix == null) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return null
            }

            if (isExclude) {
                val addressLong = ipv4ToLong(address)
                if (addressLong == null || prefix !in 0..32) {
                    bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                    return null
                }

                excludes.add(Cidr(networkOf(addressLong, prefix), prefix))
            } else {
                positives.add(address to prefix)
            }
        }

        return positives to excludes
    }

    private suspend fun addPositiveCustomRoutes(positives: List<Pair<String, Int>>, excludes: List<Cidr>): Boolean {
        positives.forEach { (address, prefix) ->
            try {
                val addressLong = ipv4ToLong(address)

                if (addressLong == null || excludes.isEmpty()) {
                    // not an IPv4 address (e.g. IPv6) or nothing to exclude: unchanged behavior
                    bridge.builder.addRoute(address, prefix)
                } else {
                    addIPv4RouteExcluding(Cidr(networkOf(addressLong, prefix), prefix), excludes)
                }
            } catch (_: IllegalArgumentException) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }
        }

        return true
    }

    internal fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        // nothing will be written until initialized
        // the position won't be changed
        outputStream?.write(buffer.array(), start, size)
        bytesIn.addAndGet(size.toLong())
    }

    internal fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        val read = inputStream?.read(buffer.array(), 0, bridge.PPP_MTU) ?: -1
        buffer.position(if (read >= 0) read else buffer.position())
        buffer.flip()
        if (read > 0) {
            bytesOut.addAndGet(read.toLong())
        }
    }

    internal fun close() {
        fd?.close()
    }
}
