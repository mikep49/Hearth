package dev.hearth.casaos.ui.apps

import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.hearthTopBarColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.AppInfoSettings
import dev.hearth.casaos.data.AppSettings
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.CpuShareLevels
import dev.hearth.casaos.data.DeviceMapping
import dev.hearth.casaos.data.EnvVar
import dev.hearth.casaos.data.PortMapping
import dev.hearth.casaos.data.RestartPolicies
import dev.hearth.casaos.data.ServiceSettings
import dev.hearth.casaos.data.VolumeMapping
import dev.hearth.casaos.data.applyAppSettings
import dev.hearth.casaos.data.looksSecret
import dev.hearth.casaos.data.normalized
import dev.hearth.casaos.data.parseAppSettings
import dev.hearth.casaos.data.validate
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.HearthSwitch
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.hearth.casaos.ui.components.readableWidth

data class AppSettingsUi(
    val loading: Boolean = false,
    val loadError: String? = null,
    /** The compose file as last loaded or saved. Form edits are written into it. */
    val yaml: String? = null,
    val settings: AppSettings? = null,
    /** The form being edited; null while just viewing. */
    val draft: AppSettings? = null,
    /** The compose file being edited as text; null unless editing YAML. */
    val yamlDraft: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val editing: Boolean get() = draft != null || yamlDraft != null

    fun hasChanges(): Boolean =
        (draft != null && draft.normalized() != settings?.normalized()) || (yamlDraft != null && yamlDraft != yaml)
}

/**
 * Shows and edits one app's settings. State lives in Compose state rather than a flow so text fields
 * update in the same frame they're typed in.
 */
class AppSettingsViewModel(private val client: CasaClient, private val appId: String) : ViewModel() {
    var ui by mutableStateOf(AppSettingsUi())
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Loads the settings unless they're already here, e.g. after rotating mid-edit. */
    fun open() {
        if (ui.settings == null && !ui.loading) load()
    }

    fun load() {
        ui = ui.copy(loading = true, loadError = null)
        viewModelScope.launch {
            try {
                val yaml = client.appComposeYaml(appId)
                val settings = withContext(Dispatchers.Default) { parseAppSettings(yaml) }
                ui = AppSettingsUi(yaml = yaml, settings = settings)
            } catch (e: CasaException) {
                ui = ui.copy(loading = false, loadError = e.message)
                if (ui.settings != null) _messages.emit("Couldn't reload: ${e.message}")
            }
        }
    }

    fun edit() {
        ui = ui.copy(draft = ui.settings, saveError = null)
    }

    fun editYaml() {
        ui = ui.copy(yamlDraft = ui.yaml, saveError = null)
    }

    fun change(draft: AppSettings) {
        ui = ui.copy(draft = draft)
    }

    fun changeYaml(text: String) {
        ui = ui.copy(yamlDraft = text)
    }

    fun cancelEdit() {
        ui = ui.copy(draft = null, yamlDraft = null, saveError = null)
    }

    /** Forgets everything so the next open loads fresh settings. */
    fun close() {
        if (!ui.saving) ui = AppSettingsUi()
    }

    fun save() {
        val state = ui
        val yaml = state.yaml ?: return
        val settings = state.settings ?: return
        if (state.saving) return
        val newYaml = try {
            when {
                // Parsing catches a typo locally, with a line number, instead of a vaguer error from CasaOS.
                state.yamlDraft != null -> state.yamlDraft.also { parseAppSettings(it) }
                state.draft != null -> {
                    val draft = state.draft.normalized()
                    val problems = draft.validate()
                    if (problems.isNotEmpty()) {
                        ui = state.copy(saveError = problems.joinToString("\n"))
                        return
                    }
                    if (draft == settings.normalized()) {
                        cancelEdit()
                        return
                    }
                    applyAppSettings(yaml, settings, draft)
                }
                else -> return
            }
        } catch (e: CasaException) {
            ui = state.copy(saveError = e.message)
            return
        }
        ui = state.copy(saving = true, saveError = null)
        viewModelScope.launch {
            try {
                client.applyAppSettings(appId, newYaml)
                ui = AppSettingsUi(yaml = newYaml, settings = parseAppSettings(newYaml))
                _messages.emit("Saved. CasaOS is applying the changes and will restart the app.")
            } catch (e: CasaException) {
                ui = ui.copy(saving = false, saveError = e.message)
            }
        }
    }
}

/**
 * An app's settings, the same ones the CasaOS dashboard's settings dialog has: image, web UI, ports,
 * volumes, environment, devices, command, and resource limits. The pencil edits them in a form; the menu
 * has the app's whole compose file as YAML for anything the form doesn't cover.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    appName: String,
    serverHost: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = viewModel.ui
    val snackbar = remember { SnackbarHostState() }
    var pendingDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.open() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    fun confirmThen(action: () -> Unit) {
        if (ui.hasChanges()) pendingDiscard = action else action()
    }
    fun close() {
        viewModel.close()
        onClose()
    }
    fun back() {
        if (ui.saving) return
        if (ui.editing) confirmThen(viewModel::cancelEdit) else close()
    }

    BackHandler { back() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // A cross while editing, as it drops the changes; otherwise the usual back arrow.
                    if (ui.editing) {
                        IconButton(onClick = ::back, enabled = !ui.saving) { Icon(Icons.Rounded.Close, "Stop editing") }
                    } else {
                        BackButton(::back)
                    }
                },
                title = {
                    Column {
                        Text(appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                ui.yamlDraft != null -> "Editing compose YAML"
                                ui.draft != null -> "Editing settings"
                                else -> "Settings"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    when {
                        ui.saving -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), strokeWidth = 2.5.dp)
                        ui.editing -> TextButton(onClick = viewModel::save) { Text("Save") }
                        ui.settings != null -> {
                            IconButton(onClick = viewModel::edit) { Icon(Icons.Rounded.Edit, "Edit settings") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Edit as YAML") },
                                        leadingIcon = { Icon(Icons.Rounded.Code, null) },
                                        onClick = { menu = false; viewModel.editYaml() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Reload") },
                                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                        enabled = !ui.loading,
                                        onClick = { menu = false; viewModel.load() },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = hearthTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            val settings = ui.settings
            when {
                settings == null && ui.loadError == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                settings == null -> EmptyState("Couldn't load settings", ui.loadError.orEmpty()) {
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                ui.yamlDraft != null -> YamlEditor(ui.yamlDraft, viewModel::changeYaml, ui.saveError, enabled = !ui.saving)
                else -> SettingsForm(
                    settings = ui.draft ?: settings,
                    editing = ui.draft != null,
                    enabled = !ui.saving,
                    error = ui.saveError,
                    serverHost = serverHost,
                    onChange = viewModel::change,
                )
            }
        }
    }

    pendingDiscard?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to these settings haven't been saved.") },
            confirmButton = { TextButton(onClick = { pendingDiscard = null; action() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { pendingDiscard = null }) { Text("Keep editing") } },
        )
    }
}

// region form

private fun <T> List<T>.replaced(i: Int, value: T): List<T> = toMutableList().also { it[i] = value }
private fun <T> List<T>.removed(i: Int): List<T> = toMutableList().also { it.removeAt(i) }

@Composable
private fun SettingsForm(
    settings: AppSettings,
    editing: Boolean,
    enabled: Boolean,
    error: String?,
    serverHost: String,
    onChange: (AppSettings) -> Unit,
) {
    val scroll = rememberScrollState()
    var serviceIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = serviceIndex.coerceIn(0, settings.services.lastIndex)
    val service = settings.services[index]
    val changeService = { s: ServiceSettings -> onChange(settings.copy(services = settings.services.replaced(index, s))) }

    // Errors show at the top; bring them into view however far down Save was pressed from.
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).readableWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) Banner(error, isError = true)
        if (editing) Banner("Saving recreates the app's container with these settings, so the app restarts.", isError = false)

        AppSection(settings.app, editing, enabled, serverHost) { onChange(settings.copy(app = it)) }

        if (settings.services.size > 1) {
            SectionHeader("Containers")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.services.forEachIndexed { i, s ->
                    FilterChip(selected = i == index, onClick = { serviceIndex = i }, label = { Text(s.name) })
                }
            }
        }

        // Keyed so per-row state (like a revealed password) doesn't carry over to another container.
        key(service.name) {
            ContainerSection(service, editing, enabled, changeService)
            PortsSection(service, editing, enabled, changeService)
            VolumesSection(service, editing, enabled, changeService)
            EnvironmentSection(service, editing, enabled, changeService)
            DevicesSection(service, editing, enabled, changeService)
            CommandSection(service, editing, enabled, changeService)
            ResourcesSection(service, editing, enabled, changeService)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppSection(app: AppInfoSettings, editing: Boolean, enabled: Boolean, serverHost: String, onChange: (AppInfoSettings) -> Unit) {
    Section("App", Icons.Rounded.Apps) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.icon.takeIf { it.startsWith("http") }, app.title, size = 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    ValueRow("Name", app.title)
                }
            }
            ValueRow("Icon URL", app.icon, mono = true)
            val path = if (app.path.isEmpty() || app.path.startsWith("/")) app.path else "/${app.path}"
            ValueRow(
                "Web UI",
                if (app.port.isEmpty()) "" else "${app.scheme}://${app.hostname.ifEmpty { serverHost }}:${app.port}$path",
                mono = true,
                empty = "None",
            )
        } else {
            OutlinedTextField(
                app.title, { onChange(app.copy(title = it)) }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("App name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            CodeField(app.icon, { onChange(app.copy(icon = it)) }, "Icon URL", enabled = enabled, keyboardType = KeyboardType.Uri)
            Text("Web UI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val schemes = listOf("http", "https")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                schemes.forEachIndexed { i, scheme ->
                    SegmentedButton(
                        selected = app.scheme == scheme,
                        onClick = { onChange(app.copy(scheme = scheme)) },
                        shape = SegmentedButtonDefaults.itemShape(i, schemes.size),
                        enabled = enabled,
                    ) { Text(scheme) }
                }
            }
            CodeField(
                app.hostname, { onChange(app.copy(hostname = it)) }, "Host", enabled = enabled,
                placeholder = serverHost, supportingText = "Leave empty to use the server's address",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeField(app.port, { onChange(app.copy(port = it)) }, "Port", Modifier.weight(1f), enabled = enabled, keyboardType = KeyboardType.Number)
                CodeField(app.path, { onChange(app.copy(path = it)) }, "Path", Modifier.weight(1.4f), enabled = enabled, placeholder = "/")
            }
        }
    }
}

@Composable
private fun ContainerSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    Section("Container", Icons.Rounded.ViewInAr) {
        if (!editing) {
            ValueRow("Docker image", s.image, mono = true)
            ValueRow("Tag", s.tag.ifEmpty { "latest" }, mono = true)
            ValueRow("Container name", s.containerName, mono = true, empty = "Chosen by Docker")
        } else {
            CodeField(s.image, { onChange(s.copy(image = it)) }, "Docker image", enabled = enabled)
            CodeField(s.tag, { onChange(s.copy(tag = it)) }, "Tag", enabled = enabled, placeholder = "latest")
            CodeField(s.containerName, { onChange(s.copy(containerName = it)) }, "Container name", enabled = enabled)
        }
        ValueRow("Network", s.network, mono = true, empty = "Default")
        if (editing) Hint("To change the network, use Edit as YAML in the menu.")
    }
}

@Composable
private fun PortsSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    Section("Ports", Icons.Rounded.Lan) {
        if (s.network == "host") Hint("This container uses the server's network directly, so its ports aren't mapped.")
        if (!editing) {
            if (s.ports.isEmpty()) None()
            s.ports.forEach { p ->
                MappingRow(p.host.ifEmpty { "(not published)" }, p.container, s.hints.ports[p.container], tag = p.protocol.uppercase())
            }
        } else {
            s.ports.forEachIndexed { i, p ->
                val update = { new: PortMapping -> onChange(s.copy(ports = s.ports.replaced(i, new))) }
                EditRow(onRemove = { onChange(s.copy(ports = s.ports.removed(i))) }, removeLabel = "Remove port", enabled = enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodeField(p.host, { update(p.copy(host = it)) }, "Host", Modifier.weight(1f), enabled = enabled, keyboardType = KeyboardType.Number)
                        CodeField(p.container, { update(p.copy(container = it)) }, "Container", Modifier.weight(1f), enabled = enabled, keyboardType = KeyboardType.Number)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("tcp", "udp").forEach { proto ->
                            FilterChip(selected = p.protocol == proto, onClick = { update(p.copy(protocol = proto)) }, label = { Text(proto.uppercase()) }, enabled = enabled)
                        }
                        s.hints.ports[p.container]?.let { Hint(it) }
                    }
                }
            }
            AddButton("Add port", enabled) { onChange(s.copy(ports = s.ports + PortMapping("", ""))) }
        }
    }
}

@Composable
private fun VolumesSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    Section("Volumes", Icons.Rounded.Folder) {
        if (!editing) {
            if (s.volumes.isEmpty()) None()
            s.volumes.forEach { v ->
                MappingRow(v.host.ifEmpty { "(anonymous volume)" }, v.container, s.hints.volumes[v.container], tag = if (v.readOnly) "READ-ONLY" else null)
            }
        } else {
            s.volumes.forEachIndexed { i, v ->
                val update = { new: VolumeMapping -> onChange(s.copy(volumes = s.volumes.replaced(i, new))) }
                EditRow(onRemove = { onChange(s.copy(volumes = s.volumes.removed(i))) }, removeLabel = "Remove volume", enabled = enabled) {
                    CodeField(v.host, { update(v.copy(host = it)) }, "Host path", enabled = enabled, placeholder = "/DATA/AppData/…")
                    CodeField(v.container, { update(v.copy(container = it)) }, "Container path", enabled = enabled)
                    s.hints.volumes[v.container]?.let { Hint(it) }
                }
            }
            AddButton("Add volume", enabled) { onChange(s.copy(volumes = s.volumes + VolumeMapping("", ""))) }
        }
    }
}

@Composable
private fun EnvironmentSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    Section("Environment variables", Icons.Rounded.DataObject) {
        if (!editing) {
            if (s.environment.isEmpty()) None()
            s.environment.forEach { EnvRow(it, s.hints.environment[it.key]) }
        } else {
            s.environment.forEachIndexed { i, e ->
                val update = { new: EnvVar -> onChange(s.copy(environment = s.environment.replaced(i, new))) }
                EditRow(onRemove = { onChange(s.copy(environment = s.environment.removed(i))) }, removeLabel = "Remove variable", enabled = enabled) {
                    CodeField(e.key, { update(e.copy(key = it)) }, "Name", enabled = enabled)
                    CodeField(e.value, { update(e.copy(value = it)) }, "Value", enabled = enabled)
                    s.hints.environment[e.key]?.let { Hint(it) }
                }
            }
            AddButton("Add variable", enabled) { onChange(s.copy(environment = s.environment + EnvVar("", ""))) }
        }
    }
}

@Composable
private fun DevicesSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    Section("Devices", Icons.Rounded.DeveloperBoard) {
        if (!editing) {
            if (s.devices.isEmpty()) None()
            s.devices.forEach { d -> MappingRow(d.host, d.container, s.hints.devices[d.container]) }
        } else {
            s.devices.forEachIndexed { i, d ->
                val update = { new: DeviceMapping -> onChange(s.copy(devices = s.devices.replaced(i, new))) }
                EditRow(onRemove = { onChange(s.copy(devices = s.devices.removed(i))) }, removeLabel = "Remove device", enabled = enabled) {
                    CodeField(d.host, { update(d.copy(host = it)) }, "Host device", enabled = enabled, placeholder = "/dev/dri")
                    CodeField(d.container, { update(d.copy(container = it)) }, "Container device", enabled = enabled, placeholder = "Same as host")
                    s.hints.devices[d.container]?.let { Hint(it) }
                }
            }
            AddButton("Add device", enabled) { onChange(s.copy(devices = s.devices + DeviceMapping("", ""))) }
        }
    }
}

@Composable
private fun CommandSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    Section("Container command", Icons.Rounded.Terminal) {
        if (!editing) {
            if (s.command.isEmpty()) None("Image default")
            else SelectionContainer { Text(s.command.joinToString(" "), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
        } else {
            StringListEditor(s.command, { onChange(s.copy(command = it)) }, enabled, itemLabel = { "Argument ${it + 1}" }, addLabel = "Add argument")
        }
    }
}

@Composable
private fun ResourcesSection(s: ServiceSettings, editing: Boolean, enabled: Boolean, onChange: (ServiceSettings) -> Unit) {
    val cpuOptions = buildList {
        add(null to "Default")
        CpuShareLevels.forEach { (value, label) -> add(value to label) }
        if (s.cpuShares != null && CpuShareLevels.none { it.first == s.cpuShares }) add(s.cpuShares to "Custom (${s.cpuShares})")
    }
    val restartOptions = listOf("" to "Not set") + RestartPolicies.map { it to it } +
        listOfNotNull(s.restart.takeIf { it.isNotEmpty() && it !in RestartPolicies }?.let { it to it })

    Section("Resources and permissions", Icons.Rounded.Speed) {
        if (!editing) {
            ValueRow("Privileged", if (s.privileged) "Yes" else "No")
            ValueRow("Memory limit", s.memoryMb.toLongOrNull()?.let(::formatMb).orEmpty(), empty = "No limit")
            ValueRow("CPU shares", cpuOptions.firstOrNull { it.first == s.cpuShares }?.second.orEmpty())
            ValueRow("Restart policy", s.restart, mono = true)
            ValueRow("Capabilities (cap_add)", s.capAdd.joinToString(", "), mono = true, empty = "None")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Privileged", style = MaterialTheme.typography.bodyLarge)
                    Hint("Gives the container full access to the server. Only turn on if the app needs it.")
                }
                HearthSwitch(checked = s.privileged, onCheckedChange = { onChange(s.copy(privileged = it)) }, enabled = enabled)
            }
            CodeField(
                s.memoryMb, { onChange(s.copy(memoryMb = it.filter(Char::isDigit))) }, "Memory limit",
                enabled = enabled, keyboardType = KeyboardType.Number, suffix = "MB", supportingText = "Leave empty for no limit",
            )
            Picker("CPU shares", s.cpuShares, cpuOptions, enabled) { onChange(s.copy(cpuShares = it)) }
            Picker("Restart policy", s.restart, restartOptions, enabled) { onChange(s.copy(restart = it)) }
            Text("Capabilities (cap_add)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StringListEditor(s.capAdd, { onChange(s.copy(capAdd = it)) }, enabled, itemLabel = { "Capability" }, addLabel = "Add capability", placeholder = "NET_ADMIN")
        }
    }
}

private fun formatMb(mb: Long): String =
    if (mb >= 1024 && mb % 1024 == 0L) "${mb / 1024} GB" else if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

// endregion

// region building blocks

@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, size = 32.dp)
                Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, mono: Boolean = false, empty: String = "Not set") {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
        } else {
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = if (mono) FontFamily.Monospace else null)
            }
        }
    }
}

@Composable
private fun None(text: String = "None") {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** `host → container` on one line, or on two when that's too long, so a path never wraps in the middle. */
@Composable
private fun MappingRow(from: String, to: String, hint: String?, tag: String? = null) {
    val text = if (from.length + to.length > 24) "$from\n→ $to" else "$from  →  $to"
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            if (tag != null) {
                Surface(
                    Modifier.padding(start = 8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        tag,
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        if (hint != null) Hint(hint)
    }
}

/** A variable whose name suggests a secret is hidden until the eye is tapped. */
@Composable
private fun EnvRow(env: EnvVar, hint: String?) {
    var revealed by remember(env.key) { mutableStateOf(false) }
    val secret = env.looksSecret()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(env.key, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                secret && !revealed -> Text("••••••••", style = MaterialTheme.typography.bodyMedium)
                env.value.isEmpty() -> Text("(empty)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                else -> SelectionContainer { Text(env.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
            }
            if (hint != null) Hint(hint)
        }
        if (secret) {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (revealed) "Hide ${env.key}" else "Show ${env.key}")
            }
        }
    }
}

@Composable
private fun EditRow(onRemove: () -> Unit, removeLabel: String, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Rounded.RemoveCircleOutline, removeLabel) }
    }
}

@Composable
private fun AddButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun StringListEditor(
    items: List<String>,
    onChange: (List<String>) -> Unit,
    enabled: Boolean,
    itemLabel: (Int) -> String,
    addLabel: String,
    placeholder: String? = null,
) {
    items.forEachIndexed { i, item ->
        EditRow(onRemove = { onChange(items.removed(i)) }, removeLabel = "Remove ${itemLabel(i).lowercase()}", enabled = enabled) {
            CodeField(item, { onChange(items.replaced(i, it)) }, itemLabel(i), enabled = enabled, placeholder = placeholder)
        }
    }
    AddButton(addLabel, enabled) { onChange(items + "") }
}

/** A text field for paths, names and numbers: monospace, no autocorrect or capitals. */
@Composable
private fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    placeholder: String? = null,
    supportingText: String? = null,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
    )
}

/** A read-only field that opens a menu of [options]. */
@Composable
private fun <T> Picker(label: String, value: T, options: List<Pair<T, String>>, enabled: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: value.toString(),
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
            singleLine = true,
        )
        // A read-only field still takes the tap for itself, so a layer on top opens the menu.
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = "Choose $label") { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (option, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

@Composable
internal fun Banner(text: String, isError: Boolean) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = bg) {
        Row(Modifier.padding(14.dp)) {
            Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, Modifier.size(20.dp), tint = fg)
            Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = fg)
        }
    }
}

@Composable
private fun YamlEditor(text: String, onChange: (String) -> Unit, error: String?, enabled: Boolean) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error != null) Banner(error, isError = true)
        Banner("The app's whole compose file. CasaOS checks it when you save, then restarts the app.", isError = false)
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = enabled,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
        )
    }
}

// endregion
