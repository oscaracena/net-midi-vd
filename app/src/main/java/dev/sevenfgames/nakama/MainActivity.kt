package dev.sevenfgames.nakama

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.net.DnsResolver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sevenfgames.nakama.ui.theme.AppTheme
import dev.sevenfgames.nakama.ui.theme.ColorStart
import dev.sevenfgames.nakama.ui.theme.ColorStarting
import dev.sevenfgames.nakama.ui.theme.ColorStop
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val LTAG = "Nakama"

enum class ConnectionState(val value: Int) {
    STOPPED(0),
    STARTING(1),
    RUNNING(2),
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    private lateinit var midi: MidiManager
    private var zdev: MidiDeviceInfo? = null
    private var serverState = ConnectionState.STOPPED

    // UI state
    private var buttonText by mutableStateOf("START Service")
    private var buttonColor by mutableStateOf(ColorStart)
    private var hostValue by mutableStateOf("zynthian.local")
    private var portValue by mutableStateOf("5504")
    private var endpointName by mutableStateOf("My Android APP")
    private var autoConnect by mutableStateOf(true)

    private val channel = Channel<String>(capacity = Int.MAX_VALUE)

    companion object {
        init { System.loadLibrary("nakama") }
        external fun isRunning(): Boolean
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Some initialization
        hostValue = getSettingsValue("remote_host", hostValue)
        portValue = getSettingsValue("remote_port", portValue)
        endpointName = getSettingsValue("ump_endpoint", endpointName)
        autoConnect = getSettingsValue("autoconnect", "false").toBoolean()

        setServerState(if (isRunning()) ConnectionState.RUNNING else ConnectionState.STOPPED)
        ensurePermissions()
        setContent { AppTheme { MainGUI() } }

        // Open our MIDI device and send it to AMidi
        midi = getSystemService(MIDI_SERVICE) as MidiManager
        zdev = midi.devices.find {
            it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) == "Network MIDI 2.0 VD"
        }
        if (zdev == null) {
            Log.e(LTAG, "Nakama MIDI device not found!")
            return
        }

        if (autoConnect) {
            setServerState(ConnectionState.STARTING)
            GlobalScope.launch {
                startMidiService(hostValue, portValue.toInt())
            }
        }
    }

    private suspend fun startMidiService(host: String, port: Int) {
        val ipAddress = getHostIPAddress(host)
        if (ipAddress.isEmpty()) {
            setServerState(ConnectionState.STOPPED)
            showMessage("ERROR: Could not connect to '$host'.")
            return
        }

        if (zdev == null) {
            Log.e(LTAG, "Nakama MIDI device not found!")
            setServerState(ConnectionState.STOPPED)
            showMessage("ERROR: Nakama MIDI device not found!")
            return
        }

        midi.openDevice(zdev, {
            // OutToApp, InFromNet
            startProcessingMidi(it, 1, 1, ipAddress, port,
                endpointName.trim().ifEmpty { "Nakama" })
            setServerState(ConnectionState.RUNNING)
            Handler(Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 1000)

        }, null)

        setSettingsValue("remote_host", host)
        setSettingsValue("remote_port", port.toString())
        setSettingsValue("ump_endpoint", endpointName.trim())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainGUI() {
        val coroutineScope = rememberCoroutineScope()
        val snackState = remember { SnackbarHostState() }

        Scaffold(
            snackbarHost = { SnackbarHost(snackState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    title = { Text("Network MIDI 2.0 Virtual Device") }
                )
            },
            content = { innerPadding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    DisposableEffect(channel) {
                        val job = coroutineScope.launch {
                            for (msg in channel) {
                                snackState.showSnackbar(msg,
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Indefinite)
                            }
                        }
                        onDispose { job.cancel() }
                    }
                    OutlinedTextField(
                        value = hostValue,
                        onValueChange = { hostValue = it.trim() },
                        label = { Text("Remote Host/IP") },
                        placeholder = { Text("zynthian.local") }
                    )
                    OutlinedTextField(
                        modifier = Modifier.padding(top = 8.dp),
                        value = portValue,
                        onValueChange = {
                            if (!it.matches(Regex("^\\d*\$")))
                                return@OutlinedTextField
                            portValue = try {
                                if (it.toInt() > 65535) "65535"
                                else if (it.toInt() <= 0) "0"
                                else it
                            } catch (e: NumberFormatException) {
                                ""
                            }
                        },
                        label = { Text("Remote Port") },
                        placeholder = { Text("5504") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 1,
                        singleLine = true,
                        value = endpointName,
                        onValueChange = { userIn ->
                            // The spec says 98, but use the last for a c-string null terminator
                            if (userIn.toByteArray().size < 98)
                                endpointName = userIn.trim { it == '\n' || it == '\r' }
                        },
                        label = { Text("UMP Endpoint Name") },
                        placeholder = { Text("Nakama") }
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = autoConnect,
                            onCheckedChange = {
                                autoConnect = it
                                coroutineScope.launch {
                                    setSettingsValue("autoconnect", it.toString())
                                }
                            }
                        )
                        Text("Enable start on launch")
                    }
                    Button(
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            contentColor = androidx.compose.ui.graphics.Color.White,
                            containerColor = buttonColor),
                        onClick = {
                            val running = isRunning()
                            if (!running) {
                                setServerState(ConnectionState.STARTING)
                                coroutineScope.launch {
                                    startMidiService(hostValue, portValue.toInt())
                                }
                            }
                            else {
                                stopProcessingMidi()
                                setServerState(ConnectionState.STOPPED)
                            }
                        },
                    ) { Text(buttonText) }
                }
            }
        )
    }

    private fun setServerState(state: ConnectionState) {
        serverState = state
        when (state) {
            ConnectionState.STOPPED -> {
                buttonText = "START Service"
                buttonColor = ColorStart
            }
            ConnectionState.STARTING -> {
                buttonText = "STARTING..."
                buttonColor = ColorStarting
            }
            ConnectionState.RUNNING -> {
                buttonText = "STOP Service"
                buttonColor = ColorStop
            }
        }
    }

    private fun ensurePermissions() {
        Log.i(LTAG, "Ensuring permissions")

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted)
                Log.i(LTAG, "Permission granted")
            else
                Log.e(LTAG, "Permission denied")
        }

        requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showMessage(msg: String) {
        channel.trySend(msg)
    }

    private fun getSettingsValue(key: String, default: String): String {
        return runBlocking {
            dataStore.data.map { prefs ->
                prefs[stringPreferencesKey(key)] ?: default
            }.first()
        }
    }

    private suspend fun setSettingsValue(key: String, value: String) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = value
        }
    }

    private suspend fun getHostIPAddress(host: String) : String {
        if (Patterns.IP_ADDRESS.matcher(host).matches())
            return host

        return suspendCoroutine { continuation ->
            val timer = Timer("DNSResolver timer", true)
            timer.schedule(10000) {
                Log.e(LTAG, "DNSResolver timed out")
                continuation.resume("")
            }

            DnsResolver.getInstance().query(
                null, host, DnsResolver.FLAG_EMPTY, mainExecutor, null,
                object : DnsResolver.Callback<List<InetAddress>> {

                    // On response, start the service or check for errors
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        timer.cancel()
                        if (answer.isEmpty() || answer[0].hostAddress == null) {
                            Log.e(LTAG, "Could not resolve host '$host'")
                            continuation.resume("")
                            return
                        }
                        val ipAddress: String = answer[0].hostAddress!!
                        Log.i(LTAG, "Resolved host '$host' to $ipAddress")
                        continuation.resume(ipAddress)
                    }

                    // On error, warn the user
                    override fun onError(error: DnsResolver.DnsException) {
                        timer.cancel()
                        Log.e(LTAG, "Error resolving host: ${error.message}")
                        continuation.resume("")
                    }
                }
            )
        }
    }

    // JNI functions (C++ side)
    private external fun startProcessingMidi(
        midiDevice: MidiDevice, outPort: Int, inPort: Int,
        host: String, port: Int, endpoint: String
    )

    private external fun stopProcessingMidi()
}
