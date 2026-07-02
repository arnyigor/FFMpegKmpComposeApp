package com.arny.ffmpegcompose.ui.home

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arny.ffmpegcompose.components.home.*
import com.arny.ffmpegcompose.data.TimeUtils
import com.arny.ffmpegcompose.data.models.ProcessingPhase
import com.arny.ffmpegcompose.data.models.TrimStrategy
import com.arny.ffmpegcompose.data.models.WhisperModelOption
import kotlin.math.roundToLong

@Composable
fun HomeScreen(component: HomeComponent) {
    val state by component.state.collectAsState()
    HomeContent(state, component)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(state: HomeUiState, callbacks: HomeCallbacks) {
    var logsExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Media Workshop", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Конвертация, обрезка и локальное распознавание речи",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { logsExpanded = !logsExpanded }) {
                        Icon(Icons.Default.Terminal, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Журнал (${state.logs.size})")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1.65f)
                        .fillMaxHeight()
                        .verticalScroll(scroll)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SourceSection(state, callbacks)
                    OperationSection(state, callbacks)
                    if (state.trimSelected) TrimSection(state, callbacks)
                    if (state.convertType == ConvertType.TRANSCRIBE) WhisperSection(state, callbacks)
                    MediaInfoSection(state)
                    if (state.isProcessing || state.processingProgress.phase != ProcessingPhase.IDLE) {
                        ProcessingCard(state)
                    }
                    ResultCard(state, callbacks)
                }

                VerticalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutputSection(state, callbacks)
                    ExtraOptionsSection(state, callbacks)
                    StartSection(state, callbacks)
                }
            }

            if (logsExpanded) {
                HorizontalDivider()
                LogsPanel(
                    logs = state.logs,
                    onClear = callbacks::onClearLogs,
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(step: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(step, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceSection(state: HomeUiState, callbacks: HomeCallbacks) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("1", "Исходный файл", "После выбора файл анализируется автоматически")
            OutlinedButton(
                onClick = callbacks::onSelectInputFile,
                enabled = !state.isProcessing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.VideoFile, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(if (state.inputFile == null) "Выбрать видео или аудио" else "Выбрать другой файл")
            }
            state.inputFile?.let { path ->
                Text(path.replace('\\', '/').substringAfterLast('/'), fontWeight = FontWeight.Medium)
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OperationSection(state: HomeUiState, callbacks: HomeCallbacks) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("2", "Операция", "Выберите, что нужно получить")
            ConvertType.entries.forEach { type ->
                val icon = when (type) {
                    ConvertType.STREAM_COPY -> Icons.Default.Bolt
                    ConvertType.CONVERT -> Icons.Default.Transform
                    ConvertType.AUDIO_EXTRACT -> Icons.Default.AudioFile
                    ConvertType.TRANSCRIBE -> Icons.Default.Subtitles
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isProcessing) {
                        callbacks.onChangeConvertType(type)
                    },
                    shape = MaterialTheme.shapes.medium,
                    color = if (state.convertType == type) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.convertType == type,
                            enabled = !state.isProcessing,
                            onClick = { callbacks.onChangeConvertType(type) },
                        )
                        Icon(icon, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(type.title, fontWeight = FontWeight.Medium)
                            Text(operationHint(type), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun operationHint(type: ConvertType): String = when (type) {
    ConvertType.STREAM_COPY -> "Быстро, без потери качества"
    ConvertType.CONVERT -> "H.264 + AAC, совместимый MP4"
    ConvertType.AUDIO_EXTRACT -> "Сохранить звуковую дорожку в WAV"
    ConvertType.TRANSCRIBE -> "Whisper через локальную .venv, TXT/SRT/VTT/JSON"
}

@Composable
private fun OutputSection(state: HomeUiState, callbacks: HomeCallbacks) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("3", "Результат", "Имя создано автоматически, его можно изменить")
            OutlinedButton(
                onClick = callbacks::onSelectOutputFile,
                enabled = !state.isProcessing && state.inputFile != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.SaveAs, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Изменить путь")
            }
            state.outputFile?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExtraOptionsSection(state: HomeUiState, callbacks: HomeCallbacks) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Дополнительно", style = MaterialTheme.typography.titleMedium)
            LabeledSwitch("Обрезать фрагмент", state.trimSelected, !state.isProcessing, callbacks::onTrimToggled)
            if (state.convertType != ConvertType.AUDIO_EXTRACT && state.convertType != ConvertType.TRANSCRIBE) {
                LabeledSwitch(
                    "Заменить аудиодорожку",
                    state.replaceAudioSelected,
                    !state.isProcessing,
                    callbacks::onAddAudioToggled,
                )
                if (state.replaceAudioSelected) {
                    OutlinedButton(onClick = callbacks::onSelectAudioFile, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.audioFile == null) "Выбрать аудио" else "Изменить аудио")
                    }
                    state.audioFile?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
    }
}

@Composable
private fun TrimSection(state: HomeUiState, callbacks: HomeCallbacks) {
    val durationMs = (state.totalDurationUs / 1_000L).coerceAtLeast(0L)
    val startMs = state.trimParams.trimStartMs ?: 0L
    val endMs = state.trimParams.trimEndMs ?: durationMs
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Границы фрагмента", style = MaterialTheme.typography.titleMedium)
            if (durationMs > 0) {
                RangeSlider(
                    value = startMs.toFloat()..endMs.toFloat(),
                    onValueChange = { range ->
                        callbacks.onTrimStartChange(range.start.roundToLong())
                        callbacks.onTrimEndChange(range.endInclusive.roundToLong())
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = !state.isProcessing,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeInput(
                    label = "Начало",
                    valueMs = startMs,
                    enabled = !state.isProcessing,
                    modifier = Modifier.weight(1f),
                    onTimeChange = callbacks::onTrimStartChange,
                )
                TimeInput(
                    label = "Конец",
                    valueMs = endMs,
                    enabled = !state.isProcessing,
                    modifier = Modifier.weight(1f),
                    onTimeChange = callbacks::onTrimEndChange,
                )
            }
            Text("Точность обрезки", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrimStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = state.trimParams.trimStrategy == strategy,
                        onClick = { callbacks.onTrimStrategyChange(strategy) },
                        label = { Text(strategyTitle(strategy)) },
                        enabled = !state.isProcessing,
                    )
                }
            }
            Text(
                "Выбрано: ${formatDurationMs((endMs - startMs).coerceAtLeast(0L))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = callbacks::onPreviewSelection,
                enabled = !state.isProcessing && state.inputFile != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Предпросмотр выбранного фрагмента")
            }
        }
    }
}

private fun strategyTitle(strategy: TrimStrategy): String = when (strategy) {
    TrimStrategy.AUTO -> "Авто"
    TrimStrategy.FAST -> "Быстро"
    TrimStrategy.ACCURATE -> "Точно"
}

@Composable
private fun TimeInput(
    label: String,
    valueMs: Long,
    enabled: Boolean,
    modifier: Modifier,
    onTimeChange: (Long?) -> Unit,
) {
    var text by remember(valueMs) { mutableStateOf(formatDurationMs(valueMs)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            runCatching { TimeUtils.parseToMs(input) }.onSuccess(onTimeChange)
        },
        label = { Text("$label, ЧЧ:ММ:СС") },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
        isError = runCatching { TimeUtils.parseToMs(text) }.isFailure,
    )
}

@Composable
private fun WhisperSection(state: HomeUiState, callbacks: HomeCallbacks) {
    var modelsExpanded by remember { mutableStateOf(false) }
    var streamsExpanded by remember { mutableStateOf(false) }
    val audioStreams = state.mediaInfo?.streams?.filter { it.codecType == "audio" }.orEmpty()
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Whisper", style = MaterialTheme.typography.titleMedium)
            if (audioStreams.isNotEmpty()) {
                Box {
                    OutlinedButton(
                        onClick = { streamsExpanded = true },
                        enabled = !state.isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val selected = audioStreams.firstOrNull { it.index == state.selectedAudioStreamIndex }
                        Text(
                            "Дорожка: #${selected?.index ?: audioStreams.first().index} ${selected?.codecName ?: audioStreams.first().codecName}",
                            Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = streamsExpanded, onDismissRequest = { streamsExpanded = false }) {
                        audioStreams.forEach { stream ->
                            DropdownMenuItem(
                                text = { Text("#${stream.index} • ${stream.codecName} • ${stream.channels ?: "?"} ch") },
                                onClick = {
                                    callbacks.onAudioStreamChange(stream.index)
                                    streamsExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            Box {
                OutlinedButton(
                    onClick = { modelsExpanded = true },
                    enabled = !state.isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Модель: ${state.whisperSettings.model.title}", Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                    WhisperModelOption.entries.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.title)
                                    Text(model.hint, style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = {
                                callbacks.onWhisperModelChange(model)
                                modelsExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.whisperSettings.language,
                onValueChange = callbacks::onWhisperLanguageChange,
                label = { Text("Язык: auto, ru, en…") },
                singleLine = true,
                enabled = !state.isProcessing,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledSwitch(
                "Пословные временные метки",
                state.whisperSettings.wordTimestamps,
                !state.isProcessing,
                callbacks::onWordTimestampsToggled,
            )
            Text(
                "При первом запуске приложение скачает Python, faster-whisper и выбранную модель. Следующие запуски работают локально.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaInfoSection(state: HomeUiState) {
    val info = state.mediaInfo ?: return
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Параметры источника", style = MaterialTheme.typography.titleMedium)
            Text(info.format.formatInfo.ifBlank { info.format.formatLongName ?: info.format.formatName })
            info.streams.forEach { stream ->
                val description = when (stream.codecType) {
                    "video" -> "Видео • ${stream.codecName} • ${stream.width ?: "?"}×${stream.height ?: "?"}"
                    "audio" -> "Аудио #${stream.index} • ${stream.codecName} • ${stream.sampleRate ?: "?"} Hz • ${stream.channels ?: "?"} ch"
                    "subtitle" -> "Субтитры #${stream.index} • ${stream.codecName}"
                    else -> "${stream.codecType} #${stream.index} • ${stream.codecName}"
                }
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProcessingCard(state: HomeUiState) {
    val progress = state.processingProgress
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isProcessing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(progress.phase.title, style = MaterialTheme.typography.titleMedium)
                    if (progress.detail.isNotBlank()) Text(progress.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            val fraction = progress.overallProgress ?: progress.phaseProgress
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            } else if (state.isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Прошло: ${formatDurationMs(progress.elapsedMs)}", style = MaterialTheme.typography.bodySmall)
                progress.estimatedRemainingMs?.let {
                    Text("Осталось: ~${formatDurationMs(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(state: HomeUiState, callbacks: HomeCallbacks) {
    state.error?.let { message ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    state.successMessage?.let { message ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(message, fontWeight = FontWeight.Medium)
                }
                state.resultFiles.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = callbacks::onOpenOutputFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть папку")
                }
            }
        }
    }
}

@Composable
private fun StartSection(state: HomeUiState, callbacks: HomeCallbacks) {
    if (state.isProcessing) {
        Button(
            onClick = callbacks::onCancelConversion,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Остановить")
        }
    } else {
        Button(
            onClick = callbacks::onStartConversion,
            enabled = state.inputFile != null && state.outputFile != null && state.mediaInfo != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.convertType == ConvertType.TRANSCRIBE) "Распознать речь" else "Начать обработку")
        }
    }
}

@Composable
private fun LogsPanel(logs: List<LogEntry>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Card(modifier.padding(12.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Технический журнал", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Очистить") }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    items(logs, key = { it.id }) { log ->
                        Text(
                            log.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when (log.level) {
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                                LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                                LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
                                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

private fun formatDurationMs(value: Long): String {
    val totalSeconds = value.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
