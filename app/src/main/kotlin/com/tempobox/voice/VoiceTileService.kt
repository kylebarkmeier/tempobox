package com.tempobox.voice

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings "Voice command" tile: one tap in the notification drawer
 * opens [VoiceCommandActivity]. The tile is a plain action, so it always
 * reports [Tile.STATE_ACTIVE] (inactive renders as if the feature were off).
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, VoiceCommandActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    /* requestCode = */ 0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            // The PendingIntent overload only exists on API 34+.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
