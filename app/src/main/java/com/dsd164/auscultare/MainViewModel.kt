package com.dsd164.auscultare

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class RecentFile(
    val fileHash: String,
    val uriString: String,
    val filename: String,
    val currentIndexMillis: Long,
    val totalDurationMillis: Long,
    val playCount: Int
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val jsonFile = File(application.filesDir, "recent_files.json")

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    val recentFiles = _recentFiles.asStateFlow()

    private val _mediaItemChannel = Channel<Pair<MediaItem, Long>>()
    val mediaItemToPlay = _mediaItemChannel.receiveAsFlow()

    init {
        loadRecentFiles()
    }

    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        result = cursor.getString(columnIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                if (cut != null) {
                    result = result.substring(cut + 1)
                }
            }
        }
        return result ?: "Unknown File"
    }

    fun loadFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val hash = calculateHash(uri) ?: return@launch
            val filename = getFileName(context, uri)

            val existingState = _recentFiles.value.find { it.fileHash == hash }
            val startPosition = existingState?.currentIndexMillis ?: 0L
            val playCount = existingState?.playCount ?: 0

            val metadata = MediaMetadata.Builder().setTitle(filename).build()
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(hash)
                .setMediaMetadata(metadata)
                .build()

            updateRecentFileState(
                hash = hash,
                uriString = uri.toString(),
                filename = filename,
                position = startPosition,
                duration = existingState?.totalDurationMillis ?: 0L,
                playCount = playCount + 1
            )

            _mediaItemChannel.send(Pair(mediaItem, startPosition))
        }
    }

    fun updatePlaybackPosition(hash: String, position: Long, duration: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingFile = _recentFiles.value.find { it.fileHash == hash } ?: return@launch

            updateRecentFileState(
                hash = hash,
                uriString = existingFile.uriString,
                filename = existingFile.filename,
                position = position,
                duration = duration,
                playCount = existingFile.playCount
            )
        }
    }

    private fun updateRecentFileState(hash: String, uriString: String, filename: String, position: Long, duration: Long, playCount: Int) {
        val currentList = _recentFiles.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.fileHash == hash }

        val updatedFile = RecentFile(
            fileHash = hash,
            uriString = uriString,
            filename = filename,
            currentIndexMillis = position,
            totalDurationMillis = duration,
            playCount = playCount
        )

        if (existingIndex != -1) {
            // If the file exists, update it in its current position.
            currentList[existingIndex] = updatedFile
        } else {
            // If it's a new file, add it to the top.
            currentList.add(0, updatedFile)
        }

        // If the list is now too large, trim it.
        val limit = 50;
        if (currentList.size > limit) {
            _recentFiles.value = currentList.take(limit)
        } else {
            _recentFiles.value = currentList
        }

        saveRecentFiles()
    }


    /**
     * Calculates a SHA-256 hash of the first 1MB of a file.
     * This is fast and unique enough for our purposes.
     */
    private fun calculateHash(uri: Uri): String? {
        val context = getApplication<Application>().applicationContext
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val messageDigest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024) // 1MB buffer
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    messageDigest.update(buffer, 0, bytesRead)
                }
                // Convert byte array to a hex string
                messageDigest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun removeFile(fileToRemove: RecentFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _recentFiles.value.toMutableList()
            val wasRemoved = currentList.removeIf { it.fileHash == fileToRemove.fileHash }

            if (wasRemoved) {
                _recentFiles.value = currentList
                saveRecentFiles()
            }
        }
    }

    private fun loadRecentFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = jsonFile.readText()
                if (jsonString.isNotBlank()) {
                    val files = Json.decodeFromString<List<RecentFile>>(jsonString)
                    _recentFiles.value = files
                }
            } catch (e: Exception) {
                // If the file is corrupt or unreadable, start with an empty list.
                _recentFiles.value = emptyList()
            }
        }
    }

    private fun saveRecentFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = Json.encodeToString(_recentFiles.value)
                jsonFile.writeText(jsonString)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}

