package com.sentinel.admin.ui.recordings

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class RecordingItem(
    val file: File,
    val name: String,
    val formattedDate: String,
    val formattedSize: String,
    val durationText: String,
    val isPlaying: Boolean = false
)

interface AudioPlayer {
    fun setDataSource(path: String)
    fun prepareAsync()
    fun start()
    fun stop()
    fun release()
    fun setOnPreparedListener(listener: (AudioPlayer) -> Unit)
    fun setOnErrorListener(listener: (AudioPlayer, Int, Int) -> Boolean)
    fun setOnCompletionListener(listener: (AudioPlayer) -> Unit)
}

class AndroidMediaPlayerAdapter(private val player: MediaPlayer = MediaPlayer()) : AudioPlayer {
    override fun setDataSource(path: String) = player.setDataSource(path)
    override fun prepareAsync() = player.prepareAsync()
    override fun start() = player.start()
    override fun stop() = player.stop()
    override fun release() = player.release()
    override fun setOnPreparedListener(listener: (AudioPlayer) -> Unit) {
        player.setOnPreparedListener { listener(this) }
    }
    override fun setOnErrorListener(listener: (AudioPlayer, Int, Int) -> Boolean) {
        player.setOnErrorListener { _, what, extra -> listener(this, what, extra) }
    }
    override fun setOnCompletionListener(listener: (AudioPlayer) -> Unit) {
        player.setOnCompletionListener { listener(this) }
    }
}

@HiltViewModel
class RecordingsViewModel internal constructor(
    private val context: Context,
    private val playerFactory: () -> AudioPlayer,
    loadOnInit: Boolean
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(context, { AndroidMediaPlayerAdapter() }, loadOnInit = true)

    internal constructor(
        context: Context,
        playerFactory: () -> AudioPlayer
    ) : this(context, playerFactory, loadOnInit = true)

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    companion object {
        private const val TAG = "Sentinel:RecordingsVM"
    }

    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

    private val _currentlyPlayingPath = MutableStateFlow<String?>(null)
    val currentlyPlayingPath: StateFlow<String?> = _currentlyPlayingPath.asStateFlow()

    private val _pendingPreparationPath = MutableStateFlow<String?>(null)
    val pendingPreparationPath: StateFlow<String?> = _pendingPreparationPath.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var mediaPlayer: AudioPlayer? = null
    private var playbackGeneration = 0L

    init {
        if (loadOnInit) {
            loadRecordings()
        }
    }

    fun loadRecordings() {
        viewModelScope.launch {
            try {
                val items = withContext(ioDispatcher) {
                    val recordingsDir = File(context.getExternalFilesDir(null), "Recordings")
                    if (!recordingsDir.exists()) {
                        recordingsDir.mkdirs()
                    }

                    val files = recordingsDir.listFiles { _, name -> name.endsWith(".wav", ignoreCase = true) }
                        ?.sortedByDescending { it.lastModified() }
                        ?: emptyList()

                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                    val playingPath = _currentlyPlayingPath.value

                    files.map { file ->
                        val len = file.length()
                        val sizeText = when {
                            len < 1024 -> "$len B"
                            len < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", len / 1024f)
                            else -> String.format(Locale.US, "%.1f MB", len / (1024f * 1024f))
                        }
                        val dateText = dateFormat.format(Date(file.lastModified()))

                        RecordingItem(
                            file = file,
                            name = file.name,
                            formattedDate = dateText,
                            formattedSize = sizeText,
                            durationText = "WAV Audio",
                            isPlaying = file.absolutePath == playingPath
                        )
                    }
                }
                _recordings.value = items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load recordings: ${e.message}", e)
            }
        }
    }

    fun playRecording(recording: RecordingItem) {
        if (_currentlyPlayingPath.value == recording.file.absolutePath ||
            _pendingPreparationPath.value == recording.file.absolutePath) {
            stopPlayback()
            return
        }
        beginPlayback(recording.file)
    }

    private fun releaseOwnedPlayer() {
        val owned = mediaPlayer
        mediaPlayer = null
        playbackGeneration++
        _pendingPreparationPath.value = null
        if (owned != null) {
            try {
                owned.stop()
            } catch (_: Exception) {
                // Valid for a player not yet prepared.
            } finally {
                owned.release()
            }
        }
    }

    private fun beginPlayback(file: File) {
        releaseOwnedPlayer()
        _playbackError.value = null
        _pendingPreparationPath.value = file.absolutePath
        val generation = playbackGeneration
        val candidate = playerFactory()
        mediaPlayer = candidate

        candidate.setOnPreparedListener { ready ->
            if (mediaPlayer !== ready || generation != playbackGeneration) {
                return@setOnPreparedListener
            }
            try {
                ready.start()
                _currentlyPlayingPath.value = file.absolutePath
                _pendingPreparationPath.value = null
                _recordings.update { list ->
                    list.map { it.copy(isPlaying = it.file.absolutePath == file.absolutePath) }
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to start player: ${failure.message}", failure)
                releaseOwnedPlayer()
                _currentlyPlayingPath.value = null
                _pendingPreparationPath.value = null
                _recordings.update { list -> list.map { it.copy(isPlaying = false) } }
                _playbackError.value = "Failed to start audio playback"
            }
        }

        candidate.setOnErrorListener { failed, _, _ ->
            if (mediaPlayer === failed && generation == playbackGeneration) {
                releaseOwnedPlayer()
                _currentlyPlayingPath.value = null
                _pendingPreparationPath.value = null
                _recordings.update { list -> list.map { it.copy(isPlaying = false) } }
                _playbackError.value = "Playback error occurred"
            }
            true
        }

        candidate.setOnCompletionListener { completed ->
            if (mediaPlayer === completed && generation == playbackGeneration) {
                releaseOwnedPlayer()
                _currentlyPlayingPath.value = null
                _pendingPreparationPath.value = null
                _recordings.update { list -> list.map { it.copy(isPlaying = false) } }
            }
        }

        try {
            candidate.setDataSource(file.absolutePath)
            candidate.prepareAsync()
        } catch (failure: Exception) {
            Log.e(TAG, "Failed to prepare audio player: ${failure.message}", failure)
            if (mediaPlayer === candidate) releaseOwnedPlayer()
            _currentlyPlayingPath.value = null
            _pendingPreparationPath.value = null
            _recordings.update { list -> list.map { it.copy(isPlaying = false) } }
            _playbackError.value = "Unable to prepare file for playback"
        }
    }

    fun stopPlayback() {
        releaseOwnedPlayer()
        _currentlyPlayingPath.value = null
        _pendingPreparationPath.value = null
        _recordings.update { list -> list.map { it.copy(isPlaying = false) } }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun deleteRecording(recording: RecordingItem) {
        viewModelScope.launch {
            if (_currentlyPlayingPath.value == recording.file.absolutePath ||
                _pendingPreparationPath.value == recording.file.absolutePath) {
                stopPlayback()
            }
            try {
                withContext(ioDispatcher) {
                    if (recording.file.exists()) {
                        recording.file.delete()
                        Log.i(TAG, "Deleted recording: ${recording.name}")
                    }
                }
                loadRecordings()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete recording: ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        releaseOwnedPlayer()
        super.onCleared()
    }
}
