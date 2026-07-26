package io.github.styx798.sillytavernmanager.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process

class DebugUiProcessTerminationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Process.killProcess(Process.myPid())
    }
}
