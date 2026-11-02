package com.davideloconte.screenlet

import android.app.Activity
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Trampoline activity launched by Android upon USB accessory attachment.
 *
 * Extracts the [UsbAccessory] instance from the launch intent, forwards it to
 * [CompanionService] to establish the communication tunnel, and finishes immediately.
 */
class UsbActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            ?.let { CompanionService.startUsb(this, it) }
        finish()
    }
}
