package com.dsd164.auscultare

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dsd164.auscultare.ui.theme.AuscultareTheme
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

@SuppressLint("DefaultLocale")
fun Long.formatTime(): String {
    val safeMillis = this.coerceAtLeast(0)
    val totalSeconds = safeMillis / 1000

    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuscultareTheme {
                MainScreen()
            }
        }
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val application = context.applicationContext as Application
        val mainViewModel: MainViewModel = viewModel(factory = MainViewModelFactory(application))

        val recentFiles by mainViewModel.recentFiles.collectAsState()

        // State for the MediaController and its playback status
        var mediaController by remember { mutableStateOf<MediaController?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentMediaTitle by remember { mutableStateOf("-") }
        var currentMediaSubTitle by remember { mutableStateOf("") }
        var currentMediaHash by remember { mutableStateOf<String?>(null) }
        var currentPosition by remember { mutableLongStateOf(0L) }
        var totalDuration by remember { mutableLongStateOf(0L) }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri: Uri? ->
                uri?.let {
                    // Take persistent permission to access this URI
                    val contentResolver = context.contentResolver
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    mainViewModel.loadFile(it)
                }
            }
        )

        LaunchedEffect(Unit) {
            mainViewModel.mediaItemToPlay.collect { (mediaItem, startPosition) ->
                mediaController?.setMediaItem(mediaItem, startPosition)
                mediaController?.prepare()
                mediaController?.play()
            }
        }

        DisposableEffect(Unit) {
            val sessionToken =
                SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

            controllerFuture.addListener(
                {
                    val controller = controllerFuture.get()
                    mediaController = controller

                    controller.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {

                            if (playbackState == Player.STATE_ENDED) {
                                isPlaying = false
                                currentPosition = totalDuration
                            }else if(playbackState == Player.STATE_IDLE) {
                                isPlaying = false
                                currentPosition = 0L
                                totalDuration = 0L
                                currentMediaTitle = ""
                                currentMediaSubTitle = ""
                            }
                        }

                        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                            currentMediaTitle = mediaMetadata.title?.toString() ?: ""
                            currentMediaSubTitle = listOfNotNull(
                                mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() },
                                mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                            ).joinToString(" — ")
                            currentMediaHash = controller.currentMediaItem?.mediaId
                            totalDuration = controller.duration.coerceAtLeast(0)
                        }
                    })
                    isPlaying = controller.isPlaying
                    currentMediaTitle = controller.mediaMetadata.title?.toString() ?: ""
                    currentMediaSubTitle = ""
                    currentMediaHash = controller.currentMediaItem?.mediaId
                    totalDuration = controller.duration
                },
                MoreExecutors.directExecutor()
            )

            onDispose {
                mediaController?.release()
            }
        }

        LaunchedEffect(isPlaying) {
            while (isPlaying) {
                val position = mediaController?.currentPosition?.coerceAtLeast(0) ?: 0L
                val duration = mediaController?.duration?.coerceAtLeast(0) ?: 0L
                currentPosition = position
                mediaController?.currentMediaItem?.mediaId?.let { hash ->
                    if (hash.isNotBlank()) {
                        mainViewModel.updatePlaybackPosition(hash, position, duration)
                    }
                }
                delay(1000)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                BottomAppBar {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { filePickerLauncher.launch(arrayOf("audio/mpeg")) }) {
                            Icon(
                                imageVector = Icons.Filled.AddCircle,
                                contentDescription = "Open",
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                PlayerSection(
                    mediaController = mediaController,
                    isPlaying = isPlaying,
                    title = currentMediaTitle,
                    subTitle = currentMediaSubTitle,
                    currentPosition = currentPosition,
                    totalDuration = totalDuration,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                RecentFilesSection(
                    files = recentFiles,
                    currentMediaHash = currentMediaHash,
                    onFileClick = { file ->
                        if (file.fileHash != currentMediaHash) {
                            mainViewModel.loadFile(file.uriString.toUri())
                        }
                    },
                    onRemoveFile = { file ->
                        if (file.fileHash == currentMediaHash) {
                            mediaController?.stop()
                            mediaController?.clearMediaItems()
                        }
                        mainViewModel.removeFile(file)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f)
                )
            }
        }

    }

    @Composable
    fun PlayerSection(
        mediaController: MediaController?,
        isPlaying: Boolean,
        title: String,
        subTitle: String,
        currentPosition: Long,
        totalDuration: Long,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(24.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                var sliderValue by remember { mutableFloatStateOf(0f) }
                var isSeeking by remember { mutableStateOf(false) }
                var wasPlayingBeforeSeek by remember { mutableStateOf(false) }

                LaunchedEffect(currentPosition) {
                    if (!isSeeking) {
                        sliderValue = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f
                    }
                }

                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        if (!isSeeking) {
                            wasPlayingBeforeSeek = isPlaying
                            mediaController?.pause()
                        }
                        isSeeking = true
                        sliderValue = newValue
                        val seekPosition = (totalDuration * sliderValue).toLong()
                        mediaController?.seekTo(seekPosition)
                    },
                    onValueChangeFinished = {
                        if (wasPlayingBeforeSeek) {
                            mediaController?.play()
                        }
                        isSeeking = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayPosition = if (isSeeking) (totalDuration * sliderValue).toLong() else currentPosition
                    Text(text = displayPosition.formatTime(), style = MaterialTheme.typography.bodyLarge)
                    Text(text = totalDuration.formatTime(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            IconButton(
                onClick = {
                    if (mediaController == null) return@IconButton

                    if (isPlaying) {
                        mediaController.pause()
                    } else {
                        mediaController.play()
                    }
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.fillMaxSize(),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    fun RecentFilesSection(
        files: List<RecentFile>,
        currentMediaHash: String?,
        onFileClick: (RecentFile) -> Unit,
        onRemoveFile: (RecentFile) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(modifier = modifier) {
            HorizontalDivider()
            LazyColumn {
                items(files, key = { it.fileHash }) { file ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = {
                            // This is where you tell the ViewModel to delete the file
                            onRemoveFile(file)
                            true // Return true to confirm the dismiss
                        }
                     )
                    val isCurrentlyPlaying = file.fileHash == currentMediaHash
                    val backgroundColor = if (isCurrentlyPlaying) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Red)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Icon(Icons.Filled.RemoveCircle, contentDescription = "Delete")
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(backgroundColor)
                                .padding(horizontal = 16.dp, vertical = 20.dp)
                                .clickable { onFileClick(file) },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                file.filename,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
                            )
                            val progressText =
                                "${file.currentIndexMillis.formatTime()} / ${file.totalDurationMillis.formatTime()}"
                            Text(
                                progressText,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                "${file.playCount}x",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}