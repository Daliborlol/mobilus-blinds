package dev.dalibor.blinds

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dalibor.blinds.gateway.Command
import dev.dalibor.blinds.gateway.Device
import dev.dalibor.blinds.gateway.GatewayException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blinds = blindsApp.blinds
        setContent {
            BlindsTheme {
                val snapshot by blinds.snapshot.collectAsState()
                var showSettings by remember { mutableStateOf(false) }
                when {
                    !snapshot.loggedIn -> LoginScreen(blinds)
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(blinds) { showSettings = false }
                    }
                    else -> HomeScreen(blinds, snapshot) { showSettings = true }
                }
            }
        }
    }
}

@Composable
fun BlindsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Material You needs Android 12. Android 11 falls back to the Material defaults.
    val colors = when {
        Build.VERSION.SDK_INT < 31 -> if (isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
        isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun LoginScreen(blinds: Blinds) {
    var host by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun search() {
        searching = true
        found = findGateways(context)
        // Never replace an address that was typed while the search was running.
        if (host.isEmpty()) found.singleOrNull()?.let { host = it }
        searching = false
    }
    LaunchedEffect(Unit) { search() }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connect to your gateway", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Enter the login you made on your COSMO GTW. Your phone needs to be on the same network.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Gateway IP address") },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                searching -> Text("Looking for your gateway…", style = MaterialTheme.typography.bodySmall)
                found.size == 1 -> Text("Found a gateway on your network", style = MaterialTheme.typography.bodySmall)
                found.size > 1 -> {
                    Text("Found several gateways. Choose one:", style = MaterialTheme.typography.bodySmall)
                    found.forEach { TextButton(onClick = { host = it }) { Text(it) } }
                }
                else -> {
                    Text(
                        "No gateway found. Check you're on the same Wi-Fi, or type its address.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { scope.launch { search() } }) { Text("Search again") }
                }
            }
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Login") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && host.isNotEmpty() && login.isNotEmpty() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            blinds.logIn(host, login, password)
                        } catch (e: GatewayException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Connect") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(blinds: Blinds, snapshot: Snapshot, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        refreshing = true
        scope.launch {
            error = blinds.refresh()
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = openSettings) {
                        Icon(painterResource(R.drawable.ic_settings), "Settings")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(refreshing, ::refresh, Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                item { BlindCard(blinds, snapshot, ALL_BLINDS, null) }
                items(snapshot.blinds, key = { it.id }) { BlindCard(blinds, snapshot, it.id, it) }
            }
        }
    }
}

@Composable
private fun BlindCard(blinds: Blinds, snapshot: Snapshot, target: Long, device: Device?) {
    val scope = rememberCoroutineScope()
    fun send(command: Command) {
        scope.launch { blinds.send(target, command) }
    }
    val percent = snapshot.open[target]
    val line = snapshot.status[target] ?: when {
        device == null -> "${snapshot.blinds.size} blinds"
        percent == null -> "Position unknown"
        percent == 0 -> "Closed"
        else -> "$percent% open"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device?.name ?: stringResource(R.string.all_blinds), style = MaterialTheme.typography.titleMedium)
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                CommandButton(R.drawable.ic_up, R.string.up) { send(Command.Up) }
                Spacer(Modifier.width(4.dp))
                CommandButton(R.drawable.ic_stop, R.string.stop) { send(Command.Stop) }
                Spacer(Modifier.width(4.dp))
                CommandButton(R.drawable.ic_down, R.string.down) { send(Command.Down) }
            }
            if (device?.supportsPosition == true) {
                var dragged by remember(percent) { mutableFloatStateOf((percent ?: 0).toFloat()) }
                Slider(
                    value = dragged,
                    onValueChange = { dragged = it },
                    onValueChangeFinished = { send(Command.position(dragged.toInt())) },
                    valueRange = 0f..100f,
                )
            }
        }
    }
}

@Composable
private fun CommandButton(icon: Int, label: Int, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(painterResource(icon), stringResource(label))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(blinds: Blinds, close: () -> Unit) {
    val context = LocalContext.current
    val snapshot by blinds.snapshot.collectAsState()
    var notification by remember { mutableStateOf(blinds.store.notificationEnabled) }

    fun setNotification(enabled: Boolean) {
        notification = enabled
        blinds.store.notificationEnabled = enabled
        LockScreenNotification.update(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setNotification(granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_back), "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Blind to control", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used by the notification, Quick Settings tiles and launcher shortcuts.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (snapshot.blinds.isEmpty()) Text("No blinds found yet.")
            snapshot.blinds.forEach { device ->
                Row(
                    Modifier.fillMaxWidth().clickable { blinds.chooseMainBlind(device.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = device.id == snapshot.mainBlind, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(device.name, Modifier.padding(vertical = 8.dp))
                }
            }

            HorizontalDivider()
            Text("Lock screen", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (notification) setNotification(false)
                    else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Notification with buttons")
                    Text(
                        "Stays on the lock screen with open, stop and close. Works on every phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = notification, onCheckedChange = null)
            }

            HorizontalDivider()
            Text("Quick Settings tiles", style = MaterialTheme.typography.titleMedium)
            Text("Available when you pull down the shade, even while locked.", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = { addTile(context, UpTile::class.java, R.string.tile_up, R.drawable.ic_up) }) {
                    Text("Add Open tile")
                }
                OutlinedButton(onClick = { addTile(context, StopTile::class.java, R.string.tile_stop, R.drawable.ic_stop) }) {
                    Text("Add Stop tile")
                }
                OutlinedButton(onClick = { addTile(context, DownTile::class.java, R.string.tile_down, R.drawable.ic_down) }) {
                    Text("Add Close tile")
                }
            } else {
                Text("Edit your Quick Settings panel and drag the Blinds tiles in.")
            }

            HorizontalDivider()
            Text("Widget", style = MaterialTheme.typography.titleMedium)
            Text(
                "Goes on the home screen, and on the lock screen on phones that allow widgets there. " +
                    "Each widget controls one blind. Added from here, it starts on the blind chosen above. " +
                    "Long-press it to change which.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                val manager = AppWidgetManager.getInstance(context)
                manager.requestPinAppWidget(ComponentName(context, BlindWidget::class.java), null, null)
            }) { Text("Add widget") }

            HorizontalDivider()
            Text("Device Controls", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add the Device Controls tile to Quick Settings, pick Blinds, and choose the controls " +
                    "you want. To use them while locked, turn on \"Control from locked device\" in your " +
                    "phone's lock screen settings.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Gateway", style = MaterialTheme.typography.titleMedium)
            blinds.store.credentials?.let { Text("${it.login} at ${it.host}") }
            OutlinedButton(onClick = {
                setNotification(false)
                blinds.logOut()
            }) { Text("Log out") }
        }
    }
}

private fun addTile(context: Context, service: Class<*>, label: Int, icon: Int) {
    if (Build.VERSION.SDK_INT < 33) return
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, service),
        context.getString(label),
        Icon.createWithResource(context, icon),
        context.mainExecutor,
    ) { }
}

/** Lets a widget pick its blind. Android returns here when placing or reconfiguring one. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val blinds = blindsApp.blinds

        fun choose(target: Long) {
            blinds.store.setWidgetTarget(widgetId, target)
            BlindWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }

        setContent {
            BlindsTheme {
                val snapshot by blinds.snapshot.collectAsState()
                Scaffold { padding ->
                    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        item {
                            Text(
                                "Choose the blind this widget controls",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            if (snapshot.blinds.isEmpty()) {
                                Text("No blinds found yet. Open the app and log in first.")
                            }
                        }
                        items(snapshot.blinds, key = { it.id }) { device ->
                            Text(
                                device.name,
                                Modifier.fillMaxWidth().clickable { choose(device.id) }.padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
