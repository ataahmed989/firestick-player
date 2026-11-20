package app.fireplayer.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import java.net.Inet4Address

/**
 * Which way the film's connection leaves the TV, and what else on the TV downloads meanwhile, for the FirePlayerDiag
 * loader line (see player-fixes-wip/THROUGHPUT.md). Never an address: only what kind of path it is.
 *
 * - "vpn=none": no VPN applies to this app.
 * - "vpn=split": a VPN (Tailscale) is on, but carries only its own addresses: the film goes straight out over Wi-Fi.
 * - "vpn=all": the VPN carries the open internet, as Tailscale does with an exit node: the film goes through it.
 * - "vpn=not-this-app": a VPN is on and leaves this app out.
 * - " dns=vpn" after either of the first two: the VPN also answers the TV's name lookups ("Use Tailscale DNS").
 *
 * Read on the log's own thread: these are calls into the system.
 */
object NetPath {

    /** The path as above; "vpn=?" where it can't be told (before Android 6). */
    fun vpn(context: Context): String = runCatching { path(context) }.getOrDefault("vpn=?")

    private fun path(context: Context): String {
        if (Build.VERSION.SDK_INT < 23) return "vpn=?"
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "vpn=?"
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (net == null || caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            @Suppress("DEPRECATION")
            val elsewhere = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            return if (elsewhere) "vpn=not-this-app" else "vpn=none"
        }
        val link = cm.getLinkProperties(net) ?: return "vpn=on"
        val routes = link.routes.mapNotNull { r -> r.destination?.let { (it.address is Inet4Address) to it.prefixLength } }
        return (if (routesInternet(routes)) "vpn=all" else "vpn=split") + if (link.dnsServers.isNotEmpty()) " dns=vpn" else ""
    }

    /**
     * Whether a VPN with these routes (IPv4 or not, and the prefix length) carries the open internet: a default route,
     * IPv4 routes over half of all addresses (an exit node that leaves the home network out comes as many smaller
     * routes), or an IPv6 route as wide as all public addresses (2000::/3). Tailscale's own addresses (100.64.0.0/10)
     * and a home subnet shared through it are far less.
     */
    fun routesInternet(routes: List<Pair<Boolean, Int>>): Boolean {
        var v4 = 0L
        for ((ipv4, length) in routes) {
            if (ipv4) v4 += 1L shl (32 - length.coerceIn(0, 32)) else if (length <= 3) return true
        }
        return v4 >= 1L shl 31
    }

    /** Bytes the whole TV, and this app, have received so far; -1 each where the system doesn't say. */
    fun received(): LongArray = runCatching {
        longArrayOf(TrafficStats.getTotalRxBytes(), TrafficStats.getUidRxBytes(Process.myUid()))
    }.getOrDefault(longArrayOf(-1L, -1L))

    /**
     * Mb/s the rest of the TV received over [ms], from [before] and [after] as [received] gives them; -1 when not known.
     * Through a VPN the film is counted twice (in the tunnel and on Wi-Fi), so "others" then comes out near its rate.
     */
    fun othersMbps(before: LongArray, after: LongArray, ms: Long): Double {
        if (ms <= 0L || before.any { it < 0L } || after.any { it < 0L }) return -1.0
        val total = after[0] - before[0]
        val own = after[1] - before[1]
        if (total < 0L || own < 0L) return -1.0
        return (total - own).coerceAtLeast(0L) * 8.0 / 1_000.0 / ms
    }
}
