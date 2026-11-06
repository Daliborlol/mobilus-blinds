package dev.dalibor.blinds

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import dev.dalibor.blinds.gateway.Command
import kotlinx.coroutines.launch

/** Runs a command on behalf of a widget or notification button. */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getLongExtra(EXTRA_TARGET, ALL_BLINDS)
        val command = Command(intent.getStringExtra(EXTRA_COMMAND) ?: return)
        val pending = goAsync()
        val app = context.blindsApp
        app.scope.launch {
            try {
                app.blinds.send(target, command)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_COMMAND = "command"

        fun pendingIntent(context: Context, target: Long, command: Command): PendingIntent {
            val intent = Intent(context, CommandReceiver::class.java)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_COMMAND, command.value)
                // Without a distinct URI, Android would treat all these intents as the same one.
                .setData(Uri.parse("blinds://$target/${command.value}"))
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** Home screen and lock screen widget, with its label kept in step with the command's progress. */
class BlindWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach(context.blindsApp.blinds.store::removeWidget)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BlindWidget::class.java))
            ids.forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val blinds = context.blindsApp.blinds
            val snapshot = blinds.snapshot.value
            val target = blinds.store.widgetTarget(widgetId) ?: snapshot.mainBlind
            val device = snapshot.blinds.firstOrNull { it.id == target }
            val views = RemoteViews(context.packageName, R.layout.widget_blind).apply {
                if (device == null) {
                    setTextViewText(R.id.title, "Tap to choose a blind")
                    setViewVisibility(R.id.buttons, View.GONE)
                    setOnClickPendingIntent(R.id.title, chooseBlindIntent(context, widgetId))
                } else {
                    setTextViewText(R.id.title, snapshot.status[device.id] ?: device.name)
                    setViewVisibility(R.id.buttons, View.VISIBLE)
                    setOnClickPendingIntent(R.id.up, CommandReceiver.pendingIntent(context, device.id, Command.Up))
                    setOnClickPendingIntent(R.id.stop, CommandReceiver.pendingIntent(context, device.id, Command.Stop))
                    setOnClickPendingIntent(R.id.down, CommandReceiver.pendingIntent(context, device.id, Command.Down))
                    setOnClickPendingIntent(R.id.title, openAppIntent(context))
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        /** For launchers that skip the picker when placing a widget, and for removed blinds. */
        private fun chooseBlindIntent(context: Context, widgetId: Int) = PendingIntent.getActivity(
            context,
            widgetId,
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Quick Settings tiles. They run from the locked shade without asking for an unlock. */
abstract class CommandTile(private val command: Command) : TileService() {
    override fun onStartListening() = show(null)

    override fun onClick() {
        val app = blindsApp
        val device = app.blinds.snapshot.value.main ?: return show(null)
        show("Sending…")
        app.scope.launch { show(app.blinds.send(device.id, command) ?: "Done") }
    }

    private fun show(subtitle: String?) {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = subtitle ?: blindsApp.blinds.snapshot.value.main?.name ?: "Choose a blind in the app"
        tile.updateTile()
    }
}

class UpTile : CommandTile(Command.Up)
class StopTile : CommandTile(Command.Stop)
class DownTile : CommandTile(Command.Down)

/** Long-press launcher shortcuts. Shows nothing and leaves the command running in the app scope. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = Command(intent.action.orEmpty().substringAfterLast('.'))
        if (command == Command.Up || command == Command.Stop || command == Command.Down) {
            val app = blindsApp
            val device = app.blinds.snapshot.value.main
            if (device == null) {
                Toast.makeText(app, "Choose a blind in the app's settings", Toast.LENGTH_LONG).show()
            } else {
                app.scope.launch { app.blinds.send(device.id, command) }
            }
        }
        finish()
    }
}

/**
 * Optional always-there notification with buttons. It's the one lock screen option that works on
 * every phone, whatever the manufacturer has done to widgets and Device Controls.
 */
object LockScreenNotification {
    private const val CHANNEL = "controls"
    private const val ID = 1

    fun update(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val blinds = context.blindsApp.blinds
        val snapshot = blinds.snapshot.value
        val allowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!blinds.store.notificationEnabled || !snapshot.loggedIn || !allowed) {
            manager.cancel(ID)
            return
        }
        // Default importance rather than low: phones hide "silent" notifications from the lock
        // screen by default. The channel makes no sound or vibration, so it is still quiet.
        val channel = NotificationChannel(CHANNEL, "Blinds controls", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        val device = snapshot.main

        fun Notification.Builder.addCommand(icon: Int, label: Int, target: Long, command: Command) = addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, icon),
                context.getString(label),
                CommandReceiver.pendingIntent(context, target, command),
            ).build(),
        )

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_blinds)
            .setContentTitle(device?.name ?: "Choose a blind")
            .setContentText(if (device == null) "Open Settings in the app" else snapshot.status[device.id])
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (device != null) {
                    addCommand(R.drawable.ic_up, R.string.up, device.id, Command.Up)
                    addCommand(R.drawable.ic_stop, R.string.stop, device.id, Command.Stop)
                    addCommand(R.drawable.ic_down, R.string.down, device.id, Command.Down)
                }
            }
            .build()
        manager.notify(ID, notification)
    }
}

/** Starting the app is all it takes: BlindsApp re-posts the notification when it starts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LockScreenNotification.update(context)
    }
}

private fun openAppIntent(context: Context) = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE,
)
