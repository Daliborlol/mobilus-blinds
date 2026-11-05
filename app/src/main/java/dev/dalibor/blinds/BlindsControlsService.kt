package dev.dalibor.blinds

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import dev.dalibor.blinds.gateway.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.function.Consumer
import java.util.concurrent.Flow as JavaFlow

/** Device Controls: tappable blinds in the system's controls panel, usable from the lock screen. */
class BlindsControlsService : ControlsProviderService() {
    private val app get() = blindsApp
    private val blinds get() = app.blinds

    private fun targets(): List<Long> = listOf(ALL_BLINDS) + blinds.snapshot.value.blinds.map { it.id }

    private fun openApp() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun createPublisherForAllAvailable(): JavaFlow.Publisher<Control> =
        Publisher(
            app.scope,
            flow {
                // Picking controls happens right after setup, or while offline: the saved list is fine.
                targets().forEach { target ->
                    emit(
                        Control.StatelessBuilder(controlId(target), openApp())
                            .setTitle(title(target))
                            .setDeviceType(DeviceTypes.TYPE_BLINDS)
                            .build(),
                    )
                }
            },
        )

    override fun createPublisherFor(controlIds: List<String>): JavaFlow.Publisher<Control> {
        val wanted = controlIds.mapNotNull(::targetId)
        return Publisher(
            app.scope,
            flow {
                app.scope.launch { blinds.refresh() }
                blinds.snapshot.collect { snapshot ->
                    wanted.forEach { target -> if (exists(target)) emit(control(target, snapshot)) }
                }
            },
        )
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val target = targetId(controlId)
        val command = when (action) {
            is BooleanAction -> if (action.newState) Command.Up else Command.Down
            is FloatAction -> Command.position(action.newValue.toInt())
            else -> null
        }
        if (target == null || command == null) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        // Answer right away so the panel stays responsive. Failures appear as the control's status.
        consumer.accept(ControlAction.RESPONSE_OK)
        val app = app
        app.scope.launch { app.blinds.send(target, command) }
    }

    private fun exists(target: Long) =
        target == ALL_BLINDS || blinds.snapshot.value.blinds.any { it.id == target }

    private fun title(target: Long) = blinds.name(target, getString(R.string.all_blinds)).orEmpty()

    private fun control(target: Long, snapshot: Snapshot): Control {
        val devices = snapshot.blinds
        val known = if (target == ALL_BLINDS) devices.mapNotNull { snapshot.open[it.id] } else listOfNotNull(snapshot.open[target])
        // "All" reads as open if anything is open, so that tapping it closes everything first.
        val percent = known.maxOrNull()
        val isOpen = (percent ?: 0) > 0
        val statusText = snapshot.status[target] ?: when (percent) {
            null -> "Unknown"
            0 -> "Closed"
            else -> if (target == ALL_BLINDS) "Open" else "$percent% open"
        }
        val button = ControlButton(isOpen, if (isOpen) getString(R.string.down) else getString(R.string.up))
        val hasPosition = devices.firstOrNull { it.id == target }?.supportsPosition == true
        val template = if (hasPosition) {
            val range = RangeTemplate("range", 0f, 100f, (percent ?: 0).toFloat(), 1f, "%.0f%%")
            ToggleRangeTemplate("toggle_range", button, range)
        } else {
            ToggleTemplate("toggle", button)
        }
        return Control.StatefulBuilder(controlId(target), openApp())
            .setTitle(title(target))
            .setDeviceType(DeviceTypes.TYPE_BLINDS)
            .setStatus(Control.STATUS_OK)
            .setStatusText(statusText)
            .setControlTemplate(template)
            .apply { if (Build.VERSION.SDK_INT >= 33) setAuthRequired(false) }
            .build()
    }

    private fun controlId(target: Long) = "blind:$target"
    private fun targetId(controlId: String) = controlId.removePrefix("blind:").toLongOrNull()
}

/** Bridges a Kotlin flow to the Reactive Streams interface, emitting only what was requested. */
private class Publisher(
    private val scope: CoroutineScope,
    private val source: Flow<Control>,
) : JavaFlow.Publisher<Control> {
    override fun subscribe(subscriber: JavaFlow.Subscriber<in Control>) {
        val demand = MutableStateFlow(0L)
        var job: Job? = null
        subscriber.onSubscribe(object : JavaFlow.Subscription {
            override fun request(n: Long) = demand.update { it + n }
            override fun cancel() {
                job?.cancel()
            }
        })
        job = scope.launch {
            source.collect { control ->
                demand.first { it > 0 }
                demand.update { it - 1 }
                subscriber.onNext(control)
            }
            subscriber.onComplete()
        }
    }
}
