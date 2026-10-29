package com.folio.reader.narration

import android.content.*
import android.os.BatteryManager
import java.util.concurrent.atomic.AtomicBoolean

/** Also guard native callbacks: scheduler constraint delivery can lag an unplug event. */
internal class PreparationPower(private val context: Context) : AutoCloseable {
    private val allowed = AtomicBoolean(false)
    val ready: Boolean get() = allowed.get()
    private fun update(intent: Intent?) {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE,100) ?: 100
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0) != 0
        allowed.set(plugged && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) &&
            scale > 0 && level * 100L >= scale * 15L)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { update(intent) }
    }
    init { update(context.registerReceiver(receiver,IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
    override fun close() { context.unregisterReceiver(receiver) }
}
