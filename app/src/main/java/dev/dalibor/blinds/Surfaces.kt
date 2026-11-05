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
import android.widget.RemoteViews
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
            val target = blinds.store.widgetTarget(widgetId)
            val name = blinds.name(target, context.getString(R.string.all_blinds)) ?: "Blind removed"
            val status = blinds.snapshot.value.status[target]
            val views = RemoteViews(context.packageName, R.layout.widget_blind).apply {
                setTextViewText(R.id.title, status ?: name)
                setOnClickPendingIntent(R.id.up, CommandReceiver.pendingIntent(context, target, Command.Up))
                setOnClickPendingIntent(R.id.stop, CommandReceiver.pendingIntent(context, target, Command.Stop))
                setOnClickPendingIntent(R.id.down, CommandReceiver.pendingIntent(context, target, Command.Down))
                setOnClickPendingIntent(R.id.title, openAppIntent(context))
            }
            manager.updateAppWidget(widgetId, views)
        }
    }
}

/** Quick Settings tiles. They run from the locked shade without asking for an unlock. */
abstract class CommandTile(private val command: Command) : TileService() {
    override fun onStartListening() = show(null)

    override fun onClick() {
        show("Sending…")
        val app = blindsApp
        app.scope.launch { show(app.blinds.send(ALL_BLINDS, command) ?: "Done") }
    }

    private fun show(subtitle: String?) {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = subtitle
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
            app.scope.launch { app.blinds.send(ALL_BLINDS, command) }
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

        fun action(icon: Int, label: Int, command: Command) = Notification.Action.Builder(
            Icon.createWithResource(context, icon),
            context.getString(label),
            CommandReceiver.pendingIntent(context, ALL_BLINDS, command),
        ).build()

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_blinds)
            .setContentTitle(context.getString(R.string.all_blinds))
            .setContentText(snapshot.status[ALL_BLINDS])
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_up, R.string.up, Command.Up))
            .addAction(action(R.drawable.ic_stop, R.string.stop, Command.Stop))
            .addAction(action(R.drawable.ic_down, R.string.down, Command.Down))
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
