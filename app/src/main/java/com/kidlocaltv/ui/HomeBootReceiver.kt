package com.kidlocaltv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** A boot entry for TVs that initially display a vendor screen instead of the selected Home. */
class HomeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val selected = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        // Selecting another launcher also turns off this boot behavior.
        if (selected?.activityInfo?.packageName != context.packageName) return

        try {
            context.startActivity(
                Intent(context, PlayerActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                ),
            )
            Log.i(TAG, "Opened selected Home after boot")
        } catch (e: RuntimeException) {
            // Some newer Android versions or vendor builds restrict background activity launches.
            Log.w(TAG, "System did not allow the selected Home to open after boot", e)
        }
    }

    private companion object {
        const val TAG = "KidLocalHomeBoot"
    }
}
