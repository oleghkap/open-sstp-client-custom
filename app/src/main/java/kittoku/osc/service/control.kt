package kittoku.osc.service

import android.content.Context
import android.content.Intent
import android.os.Build


internal fun startVpnService(context: Context, action: String) {
    val intent = Intent(context, SstpVpnService::class.java).setAction(action)

    if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
