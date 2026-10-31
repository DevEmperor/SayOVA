package net.devemperor.sayova.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import android.content.Context
import net.devemperor.sayova.MainActivity
import net.devemperor.sayova.R
import net.devemperor.sayova.sayova
import net.devemperor.sayova.ui.settings.AppLanguages

/**
 * Tile in the watch carousel: one tap on the edge button starts talking; above it the latest
 * conversation, as a reminder of what was last asked.
 */
class SayovaTileService : Material3TileService(allowDynamicTheme = false, defaultColorScheme = TILE_COLORS) {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val app = this@SayovaTileService.sayova
        app.history.load()
        val latest = app.history.conversations.value.firstOrNull()?.title
        val talk = clickable(action = launch(MainActivity.ACTION_TALK), id = "talk")
        val strings = AppLanguages.localized(this@SayovaTileService)

        val layout = primaryLayout(
            titleSlot = { text(strings.getString(R.string.app_name).layoutString) },
            mainSlot = {
                text(
                    (latest?.let { strings.getString(R.string.tile_latest, it) } ?: strings.getString(R.string.tile_description)).layoutString,
                    typography = Typography.BODY_MEDIUM,
                    maxLines = 3,
                )
            },
            bottomSlot = {
                textEdgeButton(onClick = talk, labelContent = { text(strings.getString(R.string.home_talk).layoutString) })
            },
        )
        return tile(timeline = timeline(timelineEntry(layout)))
    }

    private fun launch(action: String): ActionBuilders.LaunchAction =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(EXTRA_ACTION, ActionBuilders.AndroidStringExtra.Builder().setValue(action).build())
                    .build(),
            )
            .build()

    companion object {
        /** Tiles cannot set an intent action, so the action travels as an extra (see MainActivity). */
        const val EXTRA_ACTION = "tile_action"

        private val TILE_COLORS = ColorScheme(
            primary = 0xFF5FE3D6.toInt().argb,
            onPrimary = 0xFF03201D.toInt().argb,
            primaryContainer = 0xFF0D4A45.toInt().argb,
            onPrimaryContainer = 0xFFB0F5EE.toInt().argb,
            surfaceContainer = 0xFF171A22.toInt().argb,
            onSurface = 0xFFF2F4F8.toInt().argb,
            onSurfaceVariant = 0xFFA7AEBD.toInt().argb,
        )

        /**
         * Asks the system to redraw the tile, e.g. after a conversation was saved. The updater binds
         * to the system with the context it is given and unbinds later on a thread of its own; with
         * the conversation service's context, which stops right after, that unbind threw ("Service
         * not registered") where nothing could catch it and took the whole app down after every
         * conversation. The application context outlives it.
         */
        fun refresh(context: Context) {
            runCatching { TileService.getUpdater(context.applicationContext).requestUpdate(SayovaTileService::class.java) }
        }
    }
}
