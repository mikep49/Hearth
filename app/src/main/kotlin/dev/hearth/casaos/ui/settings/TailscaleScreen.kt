package dev.hearth.casaos.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.data.PhoneTailscale
import dev.hearth.casaos.data.PhoneTailscaleReader
import dev.hearth.casaos.data.ServerRoute
import dev.hearth.casaos.data.SshUnavailable
import dev.hearth.casaos.data.TailscaleBackend
import dev.hearth.casaos.data.TailscaleDevice
import dev.hearth.casaos.data.TailscalePath
import dev.hearth.casaos.data.TailscaleSource
import dev.hearth.casaos.data.TailscaleStatus
import dev.hearth.casaos.data.lookUpServerRoute
import dev.hearth.casaos.data.serverRoute
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.StatusDot
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.components.hearthTopBarColors
import dev.hearth.casaos.ui.components.readableWidth
import dev.hearth.casaos.ui.theme.hearth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The server's side of Tailscale, which comes over SSH and so can be unavailable while the phone's side still shows. */
sealed interface ServerTailscale {
    data object Loading : ServerTailscale

    /** Tailscale is neither on the server itself nor running as an app. */
    data object NotInstalled : ServerTailscale

    /** [needsSignIn]: the Terminal tab can fix it by saving an SSH sign-in. */
    data class Unavailable(val message: String, val needsSignIn: Boolean) : ServerTailscale

    /** [error] is set when the latest reading failed and [status] is the last good one. */
    data class Ready(val status: TailscaleStatus, val error: String? = null) : ServerTailscale
}

/** A failed reading keeps the last status on screen; with nothing to show, it replaces it. */
internal fun ServerTailscale.failed(e: SshUnavailable): ServerTailscale =
    if (this is ServerTailscale.Ready && !e.needsSignIn) copy(error = e.message)
    else ServerTailscale.Unavailable(e.message.orEmpty(), e.needsSignIn)

data class TailscaleState(
    val phone: PhoneTailscale = PhoneTailscale(),
    val route: ServerRoute = ServerRoute.Other,
    val server: ServerTailscale = ServerTailscale.Loading,
)

private const val PHONE_POLL_MS = 2_000L
private const val SERVER_POLL_MS = 5_000L

class TailscaleViewModel(
    /** The server's address as it was typed at sign-in. */
    val host: String,
    private val source: TailscaleSource,
    private val phone: PhoneTailscaleReader,
) : ViewModel() {
    private val _state = MutableStateFlow(TailscaleState(phone.read(), serverRoute(host)))
    val state: StateFlow<TailscaleState> = _state.asStateFlow()

    /** Runs until cancelled; the page scopes it to the STARTED lifecycle, so nothing is read while it's closed. */
    suspend fun poll() = coroutineScope {
        launch {
            while (true) {
                _state.update { it.copy(phone = phone.read()) }
                delay(PHONE_POLL_MS)
            }
        }
        // A name such as "casaos" doesn't say how it's reached until it's looked up.
        if (_state.value.route == ServerRoute.Other) launch {
            val route = lookUpServerRoute(host)
            _state.update { it.copy(route = route) }
        }
        launch { pollServer() }
    }

    private suspend fun pollServer() {
        try {
            while (true) {
                val next = try {
                    source.read()?.let { ServerTailscale.Ready(it) } ?: ServerTailscale.NotInstalled
                } catch (e: SshUnavailable) {
                    _state.value.server.failed(e)
                }
                _state.update { it.copy(server = next) }
                delay(SERVER_POLL_MS)
            }
        } finally {
            source.close() // don't hold an SSH login open while the page is closed
        }
    }

    fun openTailscaleApp() {
        phone.openApp()
    }
}

/**
 * The row in Settings that opens the Tailscale page. It says whether Tailscale is on, which is checked
 * again each time Hearth comes back to the front.
 */
@Composable
fun TailscaleRow(reader: PhoneTailscaleReader, onOpen: () -> Unit) {
    var phone by remember { mutableStateOf(reader.read()) }
    LifecycleResumeEffect(reader) {
        phone = reader.read()
        onPauseOrDispose {}
    }
    ListRow(
        "Tailscale",
        subtitle = phone.address?.let { "On · $it" } ?: "Off on this phone",
        icon = Icons.Rounded.Hub,
        trailing = { Chevron() },
        onClick = onOpen,
    )
}

/**
 * Settings › Tailscale: how Hearth reaches the server, Tailscale on this phone, and Tailscale on the server
 * with the other devices it knows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleScreen(
    viewModel: TailscaleViewModel,
    onClose: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = (state.server as? ServerTailscale.Ready)?.status
    // The phone as the server's Tailscale sees it, which is where its name and the path between them come from.
    val me = status?.deviceWith(state.phone.addresses)

    BackHandler(onBack = onClose)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = { Text("Tailscale") },
                colors = hearthTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionCard(connectionSummary(viewModel.host, state, me))

            SectionHeader("This phone")
            PhoneGroup(state.phone, me, viewModel::openTailscaleApp)

            SectionHeader("Server")
            when (val server = state.server) {
                ServerTailscale.Loading -> ListGroup {
                    ListRow("Checking Tailscale on the server…", leading = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) })
                }

                ServerTailscale.NotInstalled -> Notice(
                    "Tailscale wasn't found on this server, either installed on it or running as an app. " +
                        "With Tailscale on the server and on this phone, Hearth can reach the server from anywhere.",
                )

                is ServerTailscale.Unavailable -> Notice(server.message, onOpenTerminal.takeIf { server.needsSignIn })

                is ServerTailscale.Ready -> {
                    ServerGroup(server.status)
                    server.status.health.forEach { GroupFootnote(it, color = MaterialTheme.colorScheme.error) }
                    server.error?.let { GroupFootnote("$it · showing last reading", color = MaterialTheme.colorScheme.error) }
                }
            }

            val others = remember(status, me) {
                status?.peers.orEmpty().filter { it != me }.sortedWith(compareBy({ !it.online }, { it.name.lowercase() }))
            }
            if (others.isNotEmpty()) {
                SectionHeader("Other devices")
                ListGroup { others.forEach { DeviceRow(it) } }
            }
        }
    }
}

/** The card at the top: one line on how Hearth reaches the server, and what that means. */
internal data class ConnectionSummary(val title: String, val text: String, val kind: Kind) {
    enum class Kind { Tailscale, Local, Other, Problem }
}

internal fun connectionSummary(host: String, state: TailscaleState, me: TailscaleDevice?): ConnectionSummary {
    val viaTailscale = state.route == ServerRoute.Tailscale
    // The server answering over SSH shows the address works, whatever the phone's VPN looks like from here.
    val reached = state.server is ServerTailscale.Ready
    return when {
        viaTailscale && !state.phone.on && !reached -> ConnectionSummary(
            "Tailscale looks off on this phone",
            "$host is a Tailscale address. Turn Tailscale on to reach the server.",
            ConnectionSummary.Kind.Problem,
        )

        viaTailscale -> ConnectionSummary(
            "Connected through Tailscale",
            when (me?.path) {
                TailscalePath.Direct -> "This phone and the server talk to each other directly, which is the fastest way."
                TailscalePath.Relayed ->
                    "Traffic goes through Tailscale's ${me.relay?.let { "$it relay" } ?: "relay"}, which is slower than a direct connection."
                else -> "Hearth reaches $host over your tailnet."
            },
            ConnectionSummary.Kind.Tailscale,
        )

        state.route == ServerRoute.LocalNetwork -> ConnectionSummary(
            "Connected on your local network",
            "Hearth reaches $host without Tailscale. To use Hearth away from home, sign in with the server's Tailscale address.",
            ConnectionSummary.Kind.Local,
        )

        else -> ConnectionSummary("Connected without Tailscale", "Hearth reaches $host directly.", ConnectionSummary.Kind.Other)
    }
}

@Composable
private fun ConnectionCard(summary: ConnectionSummary) {
    val (icon, tint) = when (summary.kind) {
        ConnectionSummary.Kind.Tailscale -> Icons.Rounded.VpnLock to MaterialTheme.hearth.good
        ConnectionSummary.Kind.Local -> Icons.Rounded.Lan to MaterialTheme.colorScheme.primary
        ConnectionSummary.Kind.Other -> Icons.Rounded.Public to MaterialTheme.colorScheme.primary
        ConnectionSummary.Kind.Problem -> Icons.Rounded.WarningAmber to MaterialTheme.colorScheme.error
    }
    HearthCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(CardPadding), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint = tint, size = 48.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(summary.title, style = MaterialTheme.typography.titleMedium)
                Text(summary.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PhoneGroup(phone: PhoneTailscale, me: TailscaleDevice?, onOpenApp: () -> Unit) {
    ListGroup {
        InfoRow(
            "Tailscale",
            when {
                phone.on -> "On"
                phone.appInstalled -> "Off"
                else -> "Not installed"
            },
            dot = if (phone.on) MaterialTheme.hearth.good else MaterialTheme.colorScheme.outline,
        )
        if (me != null) InfoRow("Name", me.name)
        phone.addresses.forEach { InfoRow(addressLabel(it), it, copy = true) }
        if (me != null) {
            InfoRow("Path to the server", pathText(me))
            // The server counts what it received and sent, so the phone's figures are the other way round.
            if (me.rxBytes > 0 || me.txBytes > 0) {
                InfoRow("Data with the server", "${formatBytes(me.rxBytes)} sent · ${formatBytes(me.txBytes)} received")
            }
        }
        if (phone.appInstalled) {
            ListRow(
                "Open Tailscale",
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onOpenApp,
            )
        }
    }
}

@Composable
private fun ServerGroup(status: TailscaleStatus) {
    val self = status.self
    ListGroup {
        InfoRow(
            "Tailscale",
            status.backend.label,
            dot = when (status.backend) {
                TailscaleBackend.Running -> MaterialTheme.hearth.good
                TailscaleBackend.Starting -> MaterialTheme.hearth.warn
                else -> MaterialTheme.hearth.bad
            },
        )
        if (self != null) {
            InfoRow("Name", self.name)
            self.addresses.forEach { InfoRow(addressLabel(it), it, copy = true) }
            self.dnsName?.let { InfoRow("MagicDNS name", it, copy = true) }
        }
        status.tailnet?.let { InfoRow("Tailnet", it) }
        // A tailnet is often named after the account, and then one row says both.
        status.account?.takeIf { it != status.tailnet }?.let { InfoRow("Account", it) }
        exitNodeText(status)?.let { InfoRow("Exit node", it) }
        self?.subnets?.takeIf { it.isNotEmpty() }?.let { InfoRow("Shared subnets", it.joinToString(", ")) }
        self?.keyExpiry?.let { InfoRow(if (it.isBefore(Instant.now())) "Sign-in expired" else "Sign-in expires", formatDate(it)) }
        status.version?.let { version ->
            InfoRow("Version", status.container?.let { "$version · runs as the app $it" } ?: version)
        }
    }
}

@Composable
private fun DeviceRow(device: TailscaleDevice) {
    val context = LocalContext.current
    val address = device.address
    ListRow(
        device.name,
        subtitle = listOfNotNull(device.os?.let(::osName), address, "Exit node".takeIf { device.exitNode }).joinToString(" · "),
        trailing = {
            Row(Modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (device.online) MaterialTheme.hearth.good else MaterialTheme.colorScheme.outline)
                Text(
                    if (device.online) "Online" else "Offline",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = address?.let { a -> { copyToClipboard(context, device.name, a) } },
    )
}

/** One fact: what it is, and under that its value. [copy] makes a tap copy the value. */
@Composable
private fun InfoRow(label: String, value: String, copy: Boolean = false, dot: Color? = null) {
    val context = LocalContext.current
    ListRow(
        label,
        subtitle = value,
        trailing = when {
            copy -> ({ Icon(Icons.Rounded.ContentCopy, null, Modifier.padding(end = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            dot != null -> ({ StatusDot(dot, Modifier.padding(end = 8.dp)) })
            else -> null
        },
        onClick = if (copy) ({ copyToClipboard(context, label, value) }) else null,
    )
}

/** A message in place of the server's details, with a way to the Terminal tab when signing in there fixes it. */
@Composable
private fun Notice(text: String, onOpenTerminal: (() -> Unit)? = null) {
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onOpenTerminal != null) {
                FilledTonalButton(onClick = onOpenTerminal) {
                    Icon(Icons.Rounded.Terminal, null, Modifier.size(18.dp))
                    Text("Open Terminal", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and later confirm a copy on their own.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

internal fun addressLabel(address: String) = if (':' in address) "IPv6 address" else "Address"

/** How traffic gets between a device and the server, with where it goes. */
internal fun pathText(device: TailscaleDevice): String = when (device.path) {
    TailscalePath.Direct -> "Direct"
    TailscalePath.Relayed -> device.relay?.let { "Relayed through $it" } ?: "Relayed"
    TailscalePath.Idle -> "Idle"
}

/** What the server does about exit nodes, or null when it neither uses nor offers one. */
internal fun exitNodeText(status: TailscaleStatus): String? = when {
    status.exitNode != null -> "Uses ${status.exitNode?.name}"
    status.self?.offersExitNode == true -> "This server offers to be one"
    else -> null
}

/** Tailscale's name for an operating system, written the way its maker writes it. */
internal fun osName(os: String): String = when (os.lowercase()) {
    "linux" -> "Linux"
    "android" -> "Android"
    "windows" -> "Windows"
    "macos" -> "macOS"
    "ios" -> "iOS"
    "tvos" -> "tvOS"
    else -> os
}

private fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(instant)
