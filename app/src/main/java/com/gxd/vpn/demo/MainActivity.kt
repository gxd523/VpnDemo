package com.gxd.vpn.demo

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle

/**
 * Created by guoxiaodong on 2021/6/30 11:17
 */
class MainActivity : Activity() {
    companion object {
        const val REQUEST_CODE: Int = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = VpnService.prepare(this)
        if (intent == null) {
            onActivityResult(REQUEST_CODE, RESULT_OK, null)
        } else {
            startActivityForResult(intent, REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Intent(this, MyVpnService::class.java)
                .apply {
//                    action = ToyVpnService.ACTION_CONaddAddressNECT
                }
                .let(::startService)
        }
    }
}