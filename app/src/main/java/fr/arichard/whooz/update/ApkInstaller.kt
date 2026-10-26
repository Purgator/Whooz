package fr.arichard.whooz.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** APK installer. Placeholder until the self-update lands. */
object ApkInstaller {
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Unit
    }
}
