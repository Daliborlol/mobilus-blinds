package dev.dalibor.blinds

import android.app.Application
import android.content.Context
import dev.dalibor.blinds.gateway.Command
import dev.dalibor.blinds.gateway.Device
import dev.dalibor.blinds.gateway.GatewayException
import dev.dalibor.blinds.gateway.GatewaySession
import dev.dalibor.blinds.gateway.Wire
import dev.dalibor.blinds.gateway.openPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Target id meaning every blind. Real device ids are never negative. */
const val ALL_BLINDS = -1L

data class Snapshot(
    val loggedIn: Boolean,
    val blinds: List<Device>,
    /** The gateway's last report or our last command. Many motors never report, so it can be wrong. */
    val open: Map<Long, Int>,
    /** Text to show next to a target while it is busy or after it failed. */
    val status: Map<Long, String>,
)

class BlindsApp : Application() {
    /** Outlives any one widget tap or tile click, so a command still finishes when they go away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var blinds: Blinds
        private set

    override fun onCreate() {
        super.onCreate()
        blinds = Blinds(this)
        scope.launch {
            blinds.snapshot.collect {
                BlindWidget.updateAll(this@BlindsApp)
                LockScreenNotification.update(this@BlindsApp)
            }
        }
    }
}

val Context.blindsApp get() = applicationContext as BlindsApp

class Blinds(context: Context) {
    val store = Store(context)

    private val state = MutableStateFlow(
        Snapshot(store.credentials != null, store.blinds, store.openPercent, emptyMap()),
    )
    val snapshot: StateFlow<Snapshot> = state

    fun name(target: Long, allLabel: String): String? =
        if (target == ALL_BLINDS) allLabel else state.value.blinds.firstOrNull { it.id == target }?.name

    /** Checks the login against the gateway and saves it only if it works. */
    suspend fun logIn(host: String, login: String, password: String) {
        val credentials = Credentials(host, login, Wire.userKey(password))
        val (blinds, open) = fetch(credentials)
        store.credentials = credentials
        save(blinds, open)
    }

    fun logOut() {
        store.credentials = null
        state.value = Snapshot(false, emptyList(), emptyMap(), emptyMap())
    }

    /** Returns an error message, or null when it worked. */
    suspend fun refresh(): String? {
        val credentials = store.credentials ?: return "Not logged in"
        return try {
            val (blinds, open) = fetch(credentials)
            // Keep what we knew for blinds the gateway doesn't report on.
            save(blinds, state.value.open + open)
            null
        } catch (e: GatewayException) {
            e.message
        }
    }

    /** Sends a command to one blind or all of them. Returns an error message, or null when it worked. */
    suspend fun send(target: Long, command: Command): String? {
        val credentials = store.credentials ?: return "Open the app and log in first"
        val ids = if (target == ALL_BLINDS) state.value.blinds.map { it.id } else listOf(target)
        if (ids.isEmpty()) return "No blinds found yet"
        setStatus(target, "Sending…")
        return try {
            withContext(Dispatchers.IO) {
                GatewaySession.open(credentials.host, credentials.login, credentials.userKey, COMMAND_TIMEOUT_MS)
                    .use { it.send(ids, command) }
            }
            setStatus(target, null)
            openPercent(command.value)?.let { percent ->
                val open = state.value.open + ids.associateWith { percent }
                store.openPercent = open
                state.update { it.copy(open = open) }
            }
            null
        } catch (e: GatewayException) {
            setStatus(target, e.message)
            e.message
        }
    }

    private suspend fun fetch(credentials: Credentials) = withContext(Dispatchers.IO) {
        GatewaySession.open(credentials.host, credentials.login, credentials.userKey, READ_TIMEOUT_MS).use { session ->
            val blinds = session.devices().filter { it.isBlind }
                .sortedWith(compareBy({ !it.favourite }, { it.name.lowercase() }))
            val open = session.state().mapNotNull { (id, value) -> openPercent(value)?.let { id to it } }.toMap()
            blinds to open
        }
    }

    private fun save(blinds: List<Device>, open: Map<Long, Int>) {
        store.blinds = blinds
        store.openPercent = open
        state.update { it.copy(loggedIn = true, blinds = blinds, open = open) }
    }

    private fun setStatus(target: Long, text: String?) = state.update {
        it.copy(status = if (text == null) it.status - target else it.status + (target to text))
    }

    private companion object {
        // Each of connect, login and confirmation can take this long, and all three together have
        // to fit inside the time Android gives a background broadcast receiver.
        const val COMMAND_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 8_000L
    }
}
