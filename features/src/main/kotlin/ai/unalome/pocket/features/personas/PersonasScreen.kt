package ai.unalome.pocket.features.personas

import ai.unalome.pocket.chatcore.Persona
import ai.unalome.pocket.chatcore.PersonaStore
import ai.unalome.pocket.design.GlassIconButton
import ai.unalome.pocket.design.PrimaryButton
import ai.unalome.pocket.design.SecondaryButton
import ai.unalome.pocket.design.Tag
import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeIcons
import ai.unalome.pocket.design.UnalomeRadius
import ai.unalome.pocket.design.UnalomeType
import ai.unalome.pocket.design.glass
import ai.unalome.pocket.features.models.ModelController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class PersonasViewModel(private val store: PersonaStore, private val writeScope: CoroutineScope) : ViewModel() {
    val personas: StateFlow<List<Persona>> = store.personas().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(persona: Persona?, name: String, prompt: String, temperature: Double, modelId: String?) {
        writeScope.launch {
            if (persona == null) store.create(name, prompt, temperature, modelId)
            else store.update(persona.copy(name = name, systemPrompt = prompt, temperature = temperature, defaultModelId = modelId))
        }
    }

    fun duplicate(id: String) = writeScope.launch { store.duplicate(id) }
    fun delete(id: String) = writeScope.launch { store.delete(id) }
    fun reset(id: String) = writeScope.launch { store.reset(id) }
    fun isModifiedStarter(persona: Persona) = store.isModifiedStarter(persona)
}

/** The persona list, and the editor for one persona (new or existing). */
@Composable
fun PersonasScreen(viewModel: PersonasViewModel, models: ModelController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    // The editor: null closed; "" a new persona; else the id being edited.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val target = editing?.let { id -> personas.firstOrNull { it.id == id } }

    if (editing != null) {
        BackHandler { editing = null }
        PersonaEditor(
            persona = target,
            models = models,
            onSave = { name, prompt, temperature, modelId ->
                viewModel.save(target, name, prompt, temperature, modelId)
                editing = null
            },
            onCancel = { editing = null },
            modifier = modifier,
        )
        return
    }

    var deleteRequest by remember { mutableStateOf<Persona?>(null) }
    Column(modifier.fillMaxSize().background(UnalomeColor.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(UnalomeIcons.Back, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Personas", Modifier.weight(1f).semantics { heading() }, style = UnalomeType.Title, color = UnalomeColor.TextPrimary)
            GlassIconButton(UnalomeIcons.Plus, "New persona", { editing = "" })
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "A persona is a system prompt and a temperature a chat starts with. Choose one when you start a chat, or from the model menu in a chat.",
                style = UnalomeType.Footnote,
                color = UnalomeColor.TextSecondary,
            )
            Section {
                personas.forEachIndexed { index, persona ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = UnalomeColor.GlassBorder)
                    PersonaRow(
                        persona = persona,
                        modelName = persona.defaultModelId?.let { models.library.entry(it)?.displayName },
                        isModified = viewModel.isModifiedStarter(persona),
                        onEdit = { editing = persona.id },
                        onDuplicate = { viewModel.duplicate(persona.id) },
                        onDelete = { deleteRequest = persona },
                        onReset = { viewModel.reset(persona.id) },
                    )
                }
            }
        }
    }

    deleteRequest?.let { persona ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text("Delete ${persona.name}?") },
            text = { Text("Chats that use it keep their messages and continue without a persona.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRequest = null
                    viewModel.delete(persona.id)
                }) { Text("Delete", color = UnalomeColor.Error) }
            },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("Cancel") } },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }
}

@Composable
private fun PersonaRow(
    persona: Persona,
    modelName: String?,
    isModified: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit ${persona.name}", onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(persona.name, style = UnalomeType.Headline, color = UnalomeColor.TextPrimary)
                if (persona.isBuiltIn) {
                    Spacer(Modifier.width(8.dp))
                    Tag(if (isModified) "Starter, edited" else "Starter", UnalomeColor.TextTertiary)
                }
            }
            Text(persona.systemPrompt, style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary, maxLines = 2)
            val details = buildList {
                add("Temperature ${temperatureText(persona.temperature)}")
                modelName?.let(::add)
            }
            Text(details.joinToString(" · "), style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.layout.Box {
            GlassIconButton(UnalomeIcons.Menu, "More for ${persona.name}", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = UnalomeColor.BackgroundRaised) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                if (persona.isBuiltIn) {
                    DropdownMenuItem(text = { Text("Reset to original") }, enabled = isModified, onClick = { menu = false; onReset() })
                } else {
                    DropdownMenuItem(text = { Text("Delete", color = UnalomeColor.Error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun PersonaEditor(
    persona: Persona?,
    models: ModelController,
    onSave: (name: String, prompt: String, temperature: Double, modelId: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf(persona?.name ?: "") }
    var prompt by rememberSaveable { mutableStateOf(persona?.systemPrompt ?: "") }
    var temperature by rememberSaveable { mutableDoubleStateOf(persona?.temperature ?: 0.7) }
    var modelId by rememberSaveable { mutableStateOf(persona?.defaultModelId) }
    var modelMenu by remember { mutableStateOf(false) }
    val installed by models.library.installed.collectAsStateWithLifecycle()
    val catalog = models.library.catalog.models

    Column(modifier.fillMaxSize().background(UnalomeColor.Background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(UnalomeIcons.Close, "Cancel", onCancel)
            Spacer(Modifier.width(14.dp))
            Text(
                if (persona == null) "New persona" else "Edit persona",
                Modifier.weight(1f).semantics { heading() },
                style = UnalomeType.Title,
                color = UnalomeColor.TextPrimary,
            )
            PrimaryButton("Save", { onSave(name, prompt, temperature, modelId) }, enabled = name.isNotBlank() && prompt.isNotBlank())
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Field("NAME", null) {
                TextInput(name, { name = it }, "Name", singleLine = true)
            }
            Field("SYSTEM PROMPT", "Sent to the model as its system prompt, before every chat that uses this persona.") {
                TextInput(prompt, { prompt = it }, "System prompt", singleLine = false)
            }
            Field("TEMPERATURE · ${temperatureText(temperature)}", "Lower is more focused and repeatable; higher is more varied.") {
                Slider(
                    value = temperature.toFloat(),
                    onValueChange = { temperature = PersonaStore.clampTemperature(it.toDouble()) },
                    valueRange = PersonaStore.MIN_TEMPERATURE.toFloat()..PersonaStore.MAX_TEMPERATURE.toFloat(),
                    steps = 14,
                    modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "Temperature" },
                    colors = SliderDefaults.colors(thumbColor = UnalomeColor.GradientStart, activeTrackColor = UnalomeColor.GradientStart),
                )
            }
            Field("MODEL", "Choosing this persona in a chat switches to this model, if it is installed.") {
                androidx.compose.foundation.layout.Box {
                    val label = modelId?.let { id ->
                        val entryName = catalog.firstOrNull { it.id == id }?.displayName ?: id
                        if (installed.any { it.id == id }) entryName else "$entryName · Model not installed"
                    } ?: "Keep the current model"
                    SecondaryButton(label, { modelMenu = true })
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, containerColor = UnalomeColor.BackgroundRaised) {
                        DropdownMenuItem(text = { Text("Keep the current model") }, onClick = { modelId = null; modelMenu = false })
                        catalog.forEach { entry ->
                            val isInstalled = installed.any { it.id == entry.id }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(entry.displayName)
                                        if (!isInstalled) Text("Model not installed", style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
                                    }
                                },
                                onClick = { modelId = entry.id; modelMenu = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(title: String, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
        content()
        footer?.let { Text(it, style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary) }
    }
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 48.dp else 160.dp)
            .background(Color(0xFF1A1822), shape)
            .border(1.dp, UnalomeColor.GlassBorder, shape)
            .padding(14.dp)
            .semantics { contentDescription = label },
        textStyle = UnalomeType.Body.copy(color = UnalomeColor.TextPrimary),
        cursorBrush = SolidColor(UnalomeColor.GradientMiddle),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(UnalomeRadius.Card)), content = content)
}

private fun temperatureText(value: Double) = String.format(Locale.ROOT, "%.1f", value)
