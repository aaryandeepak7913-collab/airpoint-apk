package com.aaryan.airpoint

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: start or stop hand control from the notification shade. */
class AirTile : TileService() {
    override fun onStartListening() { refresh() }
    private fun refresh() { qsTile?.apply { state = if (HandService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; label = "Airpoint"; updateTile() } }
    override fun onClick() {
        if (HandService.running) { stopService(Intent(this, HandService::class.java)); refresh(); return }
        // The camera can only start from a visible screen, so the tile opens Airpoint and Airpoint starts it.
        val i = Intent(this, MainActivity::class.java).putExtra("start", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(i) }
    }
}
