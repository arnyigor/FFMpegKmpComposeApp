package com.arny.ffmpegcompose.ui.home

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arny.ffmpegcompose.components.home.*
import com.arny.ffmpegcompose.data.TimeUtils
import com.arny.ffmpegcompose.data.models.ProcessingPhase
import com.arny.ffmpegcompose.data.models.TrimStrategy
import com.arny.ffmpegcompose.data.models.WhisperModelOption
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.math.abs

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
        bottomBar = { ActionBar(state, callbacks) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            WorkflowContent(state, callbacks, Modifier.weight(1f).fillMaxWidth())

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
private fun WorkflowContent(state: HomeUiState, callbacks: HomeCallbacks, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val isWide = maxWidth >= 940.dp
        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                WorkflowMainColumn(
                    state = state,
                    callbacks = callbacks,
                    modifier = Modifier.weight(1.55f).fillMaxHeight(),
                )
                VerticalDivider()
                WorkflowSettingsColumn(
                    state = state,
                    callbacks = callbacks,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SourceSection(state, callbacks)
                OperationSection(state, callbacks)
                OutputSection(state, callbacks)
                ExtraOptionsSection(state, callbacks)
                WorkflowDetails(state, callbacks)
            }
        }
    }
}

@Composable
private fun WorkflowMainColumn(state: HomeUiState, callbacks: HomeCallbacks, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SourceSection(state, callbacks)
        OperationSection(state, callbacks)
        WorkflowDetails(state, callbacks)
    }
}

@Composable
private fun WorkflowSettingsColumn(state: HomeUiState, callbacks: HomeCallbacks, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutputSection(state, callbacks)
        ExtraOptionsSection(state, callbacks)
        if (state.inputFile == null) {
            HintCard("Начните с выбора видео или аудио — параметры и путь результата заполнятся автоматически.")
        }
    }
}

@Composable
private fun WorkflowDetails(state: HomeUiState, callbacks: HomeCallbacks) {
    if (state.mediaInfo != null) PreviewSection(state, callbacks)
    if (state.convertType == ConvertType.TRANSCRIBE) WhisperSection(state, callbacks)
    MediaInfoSection(state)
    if (state.isProcessing || state.processingProgress.phase != ProcessingPhase.IDLE) ProcessingCard(state)
    ResultCard(state, callbacks)
}

@Composable
private fun HintCard(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.TipsAndUpdates, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
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
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                path.replace('\\', '/').substringAfterLast('/'),
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationSection(state: HomeUiState, callbacks: HomeCallbacks) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("2", "Операция", "Выберите, что нужно получить")
            ConvertType.entries.chunked(2).forEach { rowTypes ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowTypes.forEach { type ->
                        OperationCard(
                            type = type,
                            selected = state.convertType == type,
                            enabled = !state.isProcessing,
                            onClick = { callbacks.onChangeConvertType(type) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OperationCard(
    type: ConvertType,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (type) {
        ConvertType.STREAM_COPY -> Icons.Default.Bolt
        ConvertType.CONVERT -> Icons.Default.Transform
        ConvertType.AUDIO_EXTRACT -> Icons.Default.AudioFile
        ConvertType.TRANSCRIBE -> Icons.Default.Subtitles
    }
    Surface(
        modifier = modifier.heightIn(min = 92.dp).clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) CardDefaults.outlinedCardBorder() else null,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(type.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                RadioButton(selected = selected, enabled = enabled, onClick = onClick)
            }
            Text(
                operationHint(type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    OutlinedButton(
                        onClick = callbacks::onSelectAudioFile,
                        enabled = !state.isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.audioFile == null) "Выбрать аудио" else "Изменить аудио")
                    }
                    state.audioFile?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
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
private fun PreviewSection(state: HomeUiState, callbacks: HomeCallbacks) {
    val durationMs = (state.totalDurationUs / 1_000L).coerceAtLeast(0L)
    val startMs = state.trimParams.trimStartMs ?: 0L
    val endMs = state.trimParams.trimEndMs ?: durationMs
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.trimSelected) "Предпросмотр и обрезка" else "Предпросмотр",
                style = MaterialTheme.typography.titleMedium,
            )
            TimelinePreview(state, callbacks, durationMs, startMs, endMs)
            if (state.trimSelected) {
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
            }
        }
    }
}

@Composable
private fun TimelinePreview(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
) {
    val preview = state.preview
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val hasVideo = state.mediaInfo?.streams?.any { it.codecType == "video" } == true
            if (hasVideo) {
                val displayFrame = preview.currentFrame
                Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    color = Color.Black,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (displayFrame != null) {
                            EncodedFrame(
                                bytes = displayFrame,
                                contentDescription = "Встроенный просмотр видео",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (preview.isPreparing || preview.isPreviewFrameLoading) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                                Text("Нажмите «Воспроизвести»", color = Color.White)
                            }
                        }
                    }
                }
            }
            if (!hasVideo) {
                Text(
                    "Для аудиофайла доступен звуковой предпросмотр.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (durationMs > 0L) {
                UnifiedTimeline(
                    durationMs = durationMs,
                    positionMs = preview.positionMs,
                    startMs = startMs,
                    endMs = endMs,
                    trimEnabled = state.trimSelected,
                    enabled = !state.isProcessing,
                    autoScroll = preview.isPlaying,
                    callbacks = callbacks,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = callbacks::onPreviewSelection,
                    enabled = !state.isProcessing && state.inputFile != null && endMs > startMs,
                ) {
                    if (preview.isPreparing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (preview.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            preview.isPlaying || preview.isPreparing -> "Пауза"
                            hasVideo -> "Воспроизвести"
                            else -> "Слушать фрагмент"
                        },
                    )
                }
                IconButton(onClick = callbacks::onStopPreview, enabled = preview.isPlaying || preview.isPreparing) {
                    Icon(Icons.Default.Stop, contentDescription = "Остановить предпросмотр")
                }
                Text(
                    "${formatDurationMs(preview.positionMs)} / ${formatDurationMs(endMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (preview.volume == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = null,
                )
                Slider(
                    value = preview.volume.toFloat(),
                    onValueChange = { callbacks.onPreviewVolumeChange(it.toInt()) },
                    onValueChangeFinished = callbacks::onPreviewVolumeCommitted,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("${preview.volume}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(42.dp))
            }
            Text(
                if (state.trimSelected) {
                    "Белый playhead перематывает видео. Фиолетовый IN и красный OUT задают границы; пустая область прокручивает шкалу при масштабе >1."
                } else {
                    "Щёлкните или перетащите белый playhead для перемотки; пустая область прокручивает шкалу при масштабе >1."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnifiedTimeline(
    durationMs: Long,
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    trimEnabled: Boolean,
    enabled: Boolean,
    autoScroll: Boolean,
    callbacks: HomeCallbacks,
) {
    val scrollState = rememberScrollState()
    var zoom by remember { mutableFloatStateOf(1f) }
    var userInteracting by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val startState by rememberUpdatedState(startMs)
    val endState by rememberUpdatedState(endMs)
    val positionState by rememberUpdatedState(positionMs)
    val primary = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.error

    LaunchedEffect(zoom) {
        if (zoom <= 1f) scrollState.scrollTo(0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Масштаб", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { zoom = (zoom / 2f).coerceAtLeast(1f) },
                enabled = zoom > 1f,
                modifier = Modifier.width(34.dp).height(30.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("−")
            }
            Text("${zoom.toInt()}×", style = MaterialTheme.typography.labelMedium)
            TextButton(
                onClick = { zoom = (zoom * 2f).coerceAtMost(8f) },
                enabled = zoom < 8f,
                modifier = Modifier.width(34.dp).height(30.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewportWidthPx = with(density) { maxWidth.toPx() }
            val contentWidthPx = viewportWidthPx * zoom
            val timelineWidth = maxWidth * zoom

            LaunchedEffect(autoScroll, positionMs, durationMs, zoom, scrollState.maxValue, userInteracting) {
                if (
                    autoScroll &&
                    durationMs > 0L &&
                    zoom > 1f &&
                    scrollState.maxValue > 0 &&
                    !scrollState.isScrollInProgress &&
                    !userInteracting
                ) {
                    val playheadPx = positionMs.coerceIn(0L, durationMs).toFloat() / durationMs * contentWidthPx
                    val visibleStart = scrollState.value.toFloat()
                    val visibleEnd = visibleStart + viewportWidthPx
                    val margin = viewportWidthPx * 0.25f
                    val target = when {
                        playheadPx < visibleStart + margin -> playheadPx - margin
                        playheadPx > visibleEnd - margin -> playheadPx - viewportWidthPx + margin
                        else -> null
                    }
                    target?.let {
                        scrollState.animateScrollTo(it.roundToInt().coerceIn(0, scrollState.maxValue))
                    }
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                Surface(color = Color(0xFF29272F), shape = MaterialTheme.shapes.small) {
                    Canvas(
                        Modifier
                            .width(timelineWidth)
                            .height(64.dp)
                            .pointerInput(durationMs, enabled, trimEnabled) {
                                if (!enabled || durationMs <= 0L) return@pointerInput
                                val hitRadius = 24.dp.toPx()
                                fun handleX(ms: Long): Float =
                                    (ms.toFloat() / durationMs * size.width).coerceIn(12f, size.width - 12f)
                                fun seekTo(x: Float) {
                                    val time = (x.coerceIn(0f, size.width.toFloat()) / size.width * durationMs).roundToLong()
                                    callbacks.onPreviewPositionChange(time)
                                }
                                detectTapGestures { offset ->
                                    val startX = handleX(startState)
                                    val endX = handleX(endState)
                                    when {
                                        trimEnabled && abs(offset.x - startX) <= hitRadius -> seekTo(startX)
                                        trimEnabled && abs(offset.x - endX) <= hitRadius -> seekTo(endX)
                                        else -> seekTo(offset.x)
                                    }
                                }
                            }
                            .pointerInput(durationMs, enabled, trimEnabled) {
                                if (!enabled || durationMs <= 0L) return@pointerInput
                                val hitRadius = 24.dp.toPx()
                                var target = TimelineDragTarget.NONE
                                var dragStartedAtX = 0f
                                var dragStartedAtMs = 0L
                                fun handleX(ms: Long): Float =
                                    (ms.toFloat() / durationMs * size.width).coerceIn(12f, size.width - 12f)
                                fun updateTarget(x: Float) {
                                    when (target) {
                                        TimelineDragTarget.START -> {
                                            val delta = ((x - dragStartedAtX) / size.width * durationMs).roundToLong()
                                            callbacks.onTrimStartChange((dragStartedAtMs + delta).coerceIn(0L, endState))
                                        }
                                        TimelineDragTarget.END -> {
                                            val delta = ((x - dragStartedAtX) / size.width * durationMs).roundToLong()
                                            callbacks.onTrimEndChange((dragStartedAtMs + delta).coerceIn(startState, durationMs))
                                        }
                                        TimelineDragTarget.PLAYHEAD -> {
                                            val time = (x.coerceIn(0f, size.width.toFloat()) / size.width * durationMs).roundToLong()
                                            callbacks.onPreviewPositionChange(time)
                                        }
                                        TimelineDragTarget.NONE -> Unit
                                    }
                                }
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val startX = handleX(startState)
                                        val endX = handleX(endState)
                                        val playheadX = handleX(positionState.coerceIn(0L, durationMs))
                                        target = when {
                                            trimEnabled && abs(offset.x - startX) <= hitRadius && abs(offset.x - startX) <= abs(offset.x - endX) -> TimelineDragTarget.START
                                            trimEnabled && abs(offset.x - endX) <= hitRadius -> TimelineDragTarget.END
                                            abs(offset.x - playheadX) <= hitRadius -> TimelineDragTarget.PLAYHEAD
                                            else -> TimelineDragTarget.NONE
                                        }
                                        userInteracting = target != TimelineDragTarget.NONE
                                        dragStartedAtX = offset.x
                                        dragStartedAtMs = when (target) {
                                            TimelineDragTarget.START -> startState
                                            TimelineDragTarget.END -> endState
                                            else -> positionState
                                        }
                                        if (target == TimelineDragTarget.PLAYHEAD) updateTarget(offset.x)
                                    },
                                    onDragEnd = {
                                        userInteracting = false
                                        target = TimelineDragTarget.NONE
                                    },
                                    onDragCancel = {
                                        userInteracting = false
                                        target = TimelineDragTarget.NONE
                                    },
                                    onDrag = { change, _ ->
                                        if (target != TimelineDragTarget.NONE) {
                                            updateTarget(change.position.x)
                                            change.consume()
                                        }
                                    },
                                )
                            },
                    ) {
                        if (durationMs <= 0L) return@Canvas
                        val width = size.width
                        val height = size.height
                        val trackTop = 10f
                        val trackBottom = height - 10f
                        val trackHeight = trackBottom - trackTop
                        val startX = startMs.toFloat() / durationMs * width
                        val endX = endMs.toFloat() / durationMs * width
                        val playheadX = positionMs.coerceIn(0L, durationMs).toFloat() / durationMs * width
                        val startHandleX = startX.coerceIn(12f, width - 12f)
                        val endHandleX = endX.coerceIn(12f, width - 12f)

                        drawRect(Color.White.copy(alpha = 0.06f), topLeft = Offset(0f, trackTop), size = Size(width, trackHeight))
                        repeat(11) { index ->
                            val x = width * index / 10f
                            drawLine(
                                Color.White.copy(alpha = if (index % 5 == 0) 0.34f else 0.2f),
                                Offset(x, trackTop),
                                Offset(x, if (index % 5 == 0) trackBottom else trackTop + trackHeight * 0.42f),
                                strokeWidth = 1f,
                            )
                        }
                        if (trimEnabled) {
                            drawRect(
                                primary.copy(alpha = 0.30f),
                                topLeft = Offset(startX.coerceIn(0f, width), trackTop),
                                size = Size((endX - startX).coerceAtLeast(0f), trackHeight),
                            )
                            drawRect(
                                Color.Black.copy(alpha = 0.58f),
                                topLeft = Offset(0f, trackTop),
                                size = Size(startX.coerceIn(0f, width), trackHeight),
                            )
                            drawRect(
                                Color.Black.copy(alpha = 0.58f),
                                topLeft = Offset(endX.coerceIn(0f, width), trackTop),
                                size = Size((width - endX).coerceAtLeast(0f), trackHeight),
                            )
                            drawLine(primary, Offset(startHandleX, 0f), Offset(startHandleX, height), strokeWidth = 5f)
                            drawCircle(primary, radius = 11f, center = Offset(startHandleX, 11f))
                            drawCircle(Color.White, radius = 4f, center = Offset(startHandleX, 11f))
                            drawLine(endColor, Offset(endHandleX, 0f), Offset(endHandleX, height), strokeWidth = 5f)
                            drawCircle(endColor, radius = 11f, center = Offset(endHandleX, height - 11f))
                            drawCircle(Color.White, radius = 4f, center = Offset(endHandleX, height - 11f))
                        }
                        drawLine(Color.White, Offset(playheadX, 0f), Offset(playheadX, height), strokeWidth = 2.5f)
                        drawCircle(Color.White, radius = 7f, center = Offset(playheadX, height / 2f))
                    }
                }
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.fillMaxWidth().height(9.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Позиция ${formatDurationMs(positionMs)}", style = MaterialTheme.typography.labelMedium)
            Text("Всего ${formatDurationMs(durationMs)}", style = MaterialTheme.typography.labelMedium)
        }
        if (trimEnabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("IN ${formatDurationMs(startMs)}", style = MaterialTheme.typography.labelMedium, color = primary)
                Text("Выделено ${formatDurationMs((endMs - startMs).coerceAtLeast(0L))}", style = MaterialTheme.typography.labelMedium)
                Text("OUT ${formatDurationMs(endMs)}", style = MaterialTheme.typography.labelMedium, color = endColor)
            }
        }
    }
}

private enum class TimelineDragTarget { NONE, START, END, PLAYHEAD }

@Composable
private fun EncodedFrame(
    bytes: ByteArray,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val skiaImage = remember(bytes) { org.jetbrains.skia.Image.makeFromEncoded(bytes) }
    val bitmap = remember(skiaImage) { skiaImage.toComposeImageBitmap() }
    DisposableEffect(skiaImage) {
        onDispose { skiaImage.close() }
    }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
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
        label = { Text("$label, ЧЧ:ММ:СС.мс") },
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
private fun ActionBar(state: HomeUiState, callbacks: HomeCallbacks) {
    val ready = state.inputFile != null && state.outputFile != null && state.mediaInfo != null
    val status = when {
        state.isProcessing -> state.processingProgress.phase.title
        state.inputFile == null -> "Выберите исходный файл"
        state.mediaInfo == null -> "Анализируем файл…"
        state.outputFile == null -> "Укажите путь результата"
        else -> "Всё готово к запуску"
    }
    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                if (ready || state.isProcessing) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(status, fontWeight = FontWeight.Medium)
                if (ready && !state.isProcessing) {
                    Text(
                        state.outputFile.orEmpty().replace('\\', '/').substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = if (state.isProcessing) callbacks::onCancelConversion else callbacks::onStartConversion,
                enabled = state.isProcessing || ready,
                colors = if (state.isProcessing) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else ButtonDefaults.buttonColors(),
                modifier = Modifier.widthIn(min = 210.dp).heightIn(min = 50.dp),
            ) {
                Icon(if (state.isProcessing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        state.isProcessing -> "Остановить"
                        state.convertType == ConvertType.TRANSCRIBE -> "Распознать речь"
                        else -> "Начать обработку"
                    },
                )
            }
        }
    }
}

@Composable
private fun LogsPanel(logs: List<LogEntry>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    Card(modifier.padding(12.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Технический журнал", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(logs.joinToString("\n") { it.message }))
                    },
                    enabled = logs.isNotEmpty(),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Копировать")
                }
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
    val safeValue = value.coerceAtLeast(0L)
    val totalSeconds = safeValue / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    val millis = safeValue % 1_000L
    return if (millis == 0L) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }
}
