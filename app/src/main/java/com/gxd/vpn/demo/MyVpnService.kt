package com.gxd.vpn.demo

import android.content.Intent
import android.net.VpnService
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.concurrent.thread

/**
 * Created by guoxiaodong on 2021/6/30 11:11
 */
class MyVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.example.android.toyvpn.START"
        const val ACTION_DISCONNECT = "com.example.android.toyvpn.STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (intent != null && ACTION_CONNECT == intent.action) {
            connect()
            START_STICKY
        } else {
//            disconnect()
            START_NOT_STICKY
        }
    }

    private fun connect() {
        thread {
            val serverAddress = InetSocketAddress("mServerName", 9999)
            DatagramChannel.open().use { tunnel ->
                // Protect the tunnel before connecting to avoid loopback.
                check(protect(tunnel.socket())) { "Cannot protect the tunnel" }
                // Connect to the server.
                tunnel.connect(serverAddress)
                // For simplicity, we use the same thread for both reading and
                // writing. Here we put the tunnel into non-blocking mode.
                tunnel.configureBlocking(false)

            }
        }
    }
}