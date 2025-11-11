package com.arny.ffmpegcompose.ui.home

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arny.ffmpegcompose.components.home.ConvertType
import com.arny.ffmpegcompose.components.home.HomeCallbacks
import com.arny.ffmpegcompose.components.home.HomeComponent
import com.arny.ffmpegcompose.components.home.HomeUiState
import com.arny.ffmpegcompose.components.home.LogEntry
import com.arny.ffmpegcompose.components.home.LogLevel
import com.arny.ffmpegcompose.data.models.ConversionProgress

@Preview
@Composable
fun HomeScreenPreview() {
    val state = HomeUiState(
        inputFile = "/mock/input.mp4",
        outputFile = "/mock/output.mp4",
        mediaInfo = null,
        logs = mutableListOf<LogEntry>().apply {
            repeat(10) {
                add(
                    LogEntry(
                        message = "Mock log entry"
                    )
                )
            }
        },
        conversionProgress = ConversionProgress(),
        error = null,
        replaceAudioSelected = true,
        audioFile = "audio.mp3"
    )
    HomeContent(state, object : HomeCallbacks {
        override fun onSelectInputFile() {
        }

        override fun onSelectOutputFile() {
        }

        override fun onSelectAudioFile() {

        }

        override fun onGetMediaInfo() {
        }

        override fun onStartConversion() {
        }

        override fun onCancelConversion() {
        }

        override fun onClearLogs() {
        }

        override fun onAddAudioToggled(checked: Boolean) {

        }

        override fun onChangeConvertType(type: ConvertType) {
        }
    })
}

@Composable
fun HomeScreen(component: HomeComponent) {
    val state by component.state.collectAsState()

    HomeContent(
        state = state,
        callbacks = component
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    callbacks: HomeCallbacks
) {
    val scrollState = rememberScrollState()
    Scaffold { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Левая панель: контролы
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ConvertOptions(state, callbacks)
                    }

                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(scrollState)
                    )
                }
            }
            // Правая панель: логи
            LogsPanel(
                logs = state.logs,
                onClear = callbacks::onClearLogs,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ConvertOptions(
    state: HomeUiState,
    callbacks: HomeCallbacks
) {
    Text(
        text = "Настройки конвертации",
        style = MaterialTheme.typography.titleLarge
    )

    // Input File
    FileSelectionCard(
        label = "Входной файл",
        file = state.inputFile,
        enabled = !state.isProcessing,
        onSelect = callbacks::onSelectInputFile
    )

    // Output File
    FileSelectionCard(
        label = "Выходной файл",
        file = state.outputFile,
        enabled = !state.isProcessing,
        onSelect = callbacks::onSelectOutputFile
    )

    if (state.replaceAudioSelected) {
        FileSelectionCard(
            label = "Аудио файл",
            file = state.audioFile,
            enabled = !state.isProcessing,
            onSelect = callbacks::onSelectAudioFile
        )
    }

    HorizontalDivider()

    OptionsCard(
        state = state,
        onSelectType = callbacks::onChangeConvertType,
        onAddAudioToggled = callbacks::onAddAudioToggled
    )

    HorizontalDivider()

    // Media Info
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Информация о файле",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = callbacks::onGetMediaInfo,
                enabled = !state.isProcessing && state.inputFile != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Info, null)
                Spacer(Modifier.width(8.dp))
                Text("Анализировать")
            }

            state.mediaInfo?.let { info ->
                HorizontalDivider()
                info.streams.firstOrNull { it.codecType == "video" }?.let { video ->
                    Column {
                        // 🔹 Основная строка: кодек, разрешение, кадры
                        Text(
                            text = "Видео: ${video.codecName} • ${video.width ?: "?"}×${video.height ?: "?"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        // 🔹 Детали: FPS, длительность, размер — из formatInfo
                        Text(
                            text = info.format.formatInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 🔹 Доп: аудио (если нужно)
                        info.streams.firstOrNull { it.codecType == "audio" }
                            ?.let { audio ->
                                Text(
                                    text = "Аудио: ${audio.codecName} • ${audio.sampleRate ?: "?"} Hz • ${audio.channels ?: "?"} ch",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                    }
                }
            }
        }
    }

    HorizontalDivider()

    // Progress
    if (state.isProcessing) {
        ProgressCard(state)
    }

    // Actions
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = callbacks::onStartConversion,
            enabled = !state.isProcessing &&
                    state.inputFile != null &&
                    state.outputFile != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Конвертировать")
        }

        if (state.isProcessing) {
            Button(
                onClick = callbacks::onCancelConversion,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Close, null)
                Spacer(Modifier.width(8.dp))
                Text("Отменить")
            }
        }
    }

    // Messages
    state.error?.let { error ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    state.successMessage?.let { message ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun OptionsCard(
    state: HomeUiState,
    onSelectType: (ConvertType) -> Unit,
    onAddAudioToggled: (Boolean) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Настройки конвертации",
                style = MaterialTheme.typography.titleLarge
            )
            ConvertSelector(
                types = ConvertType.entries,
                selected = state.convertType.title,
                onSelected = onSelectType
            )
            ToggleButton(
                title = "Заменить аудио",
                selected = state.replaceAudioSelected,
                enabled = !state.isProcessing,
                onToggle = onAddAudioToggled,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertSelector(
    types: List<ConvertType>,
    selected: String?,
    onSelected: (ConvertType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected ?: "Выберите типа конвертации",
            readOnly = true,
            onValueChange = {},
            label = { Text("Типа конвертации") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            types.forEach { codec ->
                DropdownMenuItem(
                    text = { Text(codec.title) },
                    onClick = {
                        onSelected(codec)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
fun ToggleButton(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            enabled = enabled,
            checked = selected,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun FileSelectionCard(
    label: String,
    file: String?,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall
            )

            Button(
                enabled = enabled,
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Folder, null)
                Spacer(Modifier.width(8.dp))
                Text(if (file == null) "Выбрать файл" else "Изменить")
            }

            file?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(state: HomeUiState) {
    val progress = state.conversionProgress
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Прогресс",
                style = MaterialTheme.typography.titleMedium
            )

            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Кадр: ${progress.frame}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "FPS: %.1f".format(progress.fps),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Время: ${progress.formatTime()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Скорость: ${progress.speed}x",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                Text(
                    text = "Размер: ${progress.formatSize()}",
                    style = MaterialTheme.typography.bodySmall
                )

                val totalDurationMs = state.totalDurationMs
                val outTimeMicros = progress.outTimeMs
                val remainingTimeMs = totalDurationMs - outTimeMicros
                val remainingSeconds = remainingTimeMs / 1_000_000.0
                Text(
                    text = "Осталось: ${remainingSeconds}s",
                    style = MaterialTheme.typography.bodySmall
                )

                val percentage = if (totalDurationMs > 0) {
                    (outTimeMicros.toDouble() / totalDurationMs) * 100.0
                } else 0.0
                Text(
                    text = "Процент: ${percentage.toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun LogsPanel(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val lazyListState = rememberLazyListState()

    Card(modifier = modifier.padding(16.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Консоль (${logs.size})",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Кнопка "Копировать все"
                    IconButton(
                        onClick = {
                            val allLogsText = logs.joinToString(separator = "\n") { log ->
                                "[${log.level.name}] ${log.message}"
                            }
                            clipboardManager.setText(AnnotatedString(allLogsText))
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, "Копировать все")
                    }

                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, "Очистить")
                    }
                }
            }

            HorizontalDivider()

            Box {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    reverseLayout = true
                ) {
                    items(
                        items = logs.asReversed(),
                        key = { it.id }
                    ) { log ->
                        SelectionContainer {
                            LogEntryItem(log)
                        }
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 4.dp),
                    adapter = rememberScrollbarAdapter(lazyListState)
                )
            }
        }
    }
}

@Composable
private fun LogEntryItem(log: LogEntry) {
    val color = when (log.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = log.message,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = color,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}