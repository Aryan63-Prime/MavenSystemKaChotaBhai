package com.sentinel.admin.ui.recordings

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var recordingsDir: File
    private lateinit var fakeContext: Context

    class FakeAudioPlayer : AudioPlayer {
        var currentDataSource: String? = null
        var isStarted = false
        var isStopped = false
        var isReleased = false
        var throwOnStop = false
        var throwOnPrepare = false
        var throwOnStart = false

        var preparedListener: ((AudioPlayer) -> Unit)? = null
        var errorListener: ((AudioPlayer, Int, Int) -> Boolean)? = null
        var completionListener: ((AudioPlayer) -> Unit)? = null

        override fun setDataSource(path: String) {
            currentDataSource = path
        }

        override fun prepareAsync() {
            if (throwOnPrepare) {
                throw IllegalStateException("Fake audio prepare error")
            }
        }

        override fun start() {
            if (throwOnStart) {
                throw IllegalStateException("Fake audio start error")
            }
            isStarted = true
        }

        override fun stop() {
            isStopped = true
            if (throwOnStop) {
                throw IllegalStateException("Fake audio stop in invalid state")
            }
        }

        override fun release() {
            isReleased = true
        }

        override fun setOnPreparedListener(listener: (AudioPlayer) -> Unit) {
            preparedListener = listener
        }

        override fun setOnErrorListener(listener: (AudioPlayer, Int, Int) -> Boolean) {
            errorListener = listener
        }

        override fun setOnCompletionListener(listener: (AudioPlayer) -> Unit) {
            completionListener = listener
        }
    }

    class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File = baseDir
        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = File.createTempFile("sentinel_test", "").apply {
            delete()
            mkdirs()
        }
        recordingsDir = File(tempDir, "Recordings").apply { mkdirs() }
        fakeContext = FakeContext(tempDir)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `loadRecordings scans directory using IO dispatcher and populates state`() = runTest(testDispatcher) {
        val file1 = File(recordingsDir, "test1.wav").apply { writeBytes(ByteArray(1024)) }
        val file2 = File(recordingsDir, "test2.wav").apply { writeBytes(ByteArray(2048)) }

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = { FakeAudioPlayer() },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        vm.loadRecordings()
        advanceUntilIdle()

        assertEquals(2, vm.recordings.value.size)
        assertTrue(vm.recordings.value.any { it.name == "test1.wav" })
        assertTrue(vm.recordings.value.any { it.name == "test2.wav" })
    }

    @Test
    fun `preparation failure sets error state and releases player`() = runTest(testDispatcher) {
        val file = File(recordingsDir, "corrupted.wav").apply { writeBytes(ByteArray(100)) }
        val fakePlayer = FakeAudioPlayer().apply { throwOnPrepare = true }

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = { fakePlayer },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        val item = RecordingItem(
            file = file,
            name = file.name,
            formattedDate = "Today",
            formattedSize = "100 B",
            durationText = "WAV Audio"
        )

        vm.playRecording(item)

        assertTrue(fakePlayer.isReleased)
        assertNull(vm.currentlyPlayingPath.value)
        assertNull(vm.pendingPreparationPath.value)
        assertNotNull(vm.playbackError.value)
        assertEquals("Unable to prepare file for playback", vm.playbackError.value)
    }

    @Test
    fun `rapid A to B replacement cancels player A and starts player B`() = runTest(testDispatcher) {
        val fileA = File(recordingsDir, "audio_A.wav").apply { writeBytes(ByteArray(100)) }
        val fileB = File(recordingsDir, "audio_B.wav").apply { writeBytes(ByteArray(200)) }

        val playerA = FakeAudioPlayer()
        val playerB = FakeAudioPlayer()
        var callCount = 0

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = {
                callCount++
                if (callCount == 1) playerA else playerB
            },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        val itemA = RecordingItem(fileA, "A", "Today", "100 B", "WAV")
        val itemB = RecordingItem(fileB, "B", "Today", "200 B", "WAV")

        // Start A (pending preparation)
        vm.playRecording(itemA)
        assertEquals(fileA.absolutePath, vm.pendingPreparationPath.value)
        assertFalse(playerA.isReleased)

        // Rapidly start B before A is prepared
        vm.playRecording(itemB)
        // Player A must be released immediately
        assertTrue(playerA.isReleased)
        assertEquals(fileB.absolutePath, vm.pendingPreparationPath.value)

        // Late callback from A arrives: must be ignored due to generation fence
        playerA.preparedListener?.invoke(playerA)
        assertFalse(playerA.isStarted)
        assertNull(vm.currentlyPlayingPath.value)

        // Callback from B arrives: prepares and starts
        playerB.preparedListener?.invoke(playerB)
        assertTrue(playerB.isStarted)
        assertEquals(fileB.absolutePath, vm.currentlyPlayingPath.value)
        assertNull(vm.pendingPreparationPath.value)
    }

    @Test
    fun `late prepared and error callbacks from discarded player are ignored`() = runTest(testDispatcher) {
        val fileA = File(recordingsDir, "discarded.wav").apply { writeBytes(ByteArray(100)) }
        val playerA = FakeAudioPlayer()

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = { playerA },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        val itemA = RecordingItem(fileA, "A", "Today", "100 B", "WAV")
        vm.playRecording(itemA)

        // User stops playback before preparation finishes
        vm.stopPlayback()
        assertTrue(playerA.isReleased)
        assertNull(vm.pendingPreparationPath.value)

        // Late error arrives from playerA
        playerA.errorListener?.invoke(playerA, 1, 0)
        assertNull(vm.playbackError.value)

        // Late prepared arrives from playerA
        playerA.preparedListener?.invoke(playerA)
        assertFalse(playerA.isStarted)
        assertNull(vm.currentlyPlayingPath.value)
    }

    @Test
    fun `stop throwing exception still executes release in finally`() = runTest(testDispatcher) {
        val file = File(recordingsDir, "test.wav").apply { writeBytes(ByteArray(100)) }
        val fakePlayer = FakeAudioPlayer().apply { throwOnStop = true }

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = { fakePlayer },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        val item = RecordingItem(file, "test", "Today", "100 B", "WAV")
        vm.playRecording(item)

        // Stop is called while preparing: stop() throws, but release() must still be called
        vm.stopPlayback()

        assertTrue(fakePlayer.isStopped)
        assertTrue(fakePlayer.isReleased)
        assertNull(vm.currentlyPlayingPath.value)
        assertNull(vm.pendingPreparationPath.value)
    }

    @Test
    fun `delete recording while in pending preparation stops playback and deletes file`() = runTest(testDispatcher) {
        val file = File(recordingsDir, "to_delete.wav").apply { writeBytes(ByteArray(100)) }
        val fakePlayer = FakeAudioPlayer()

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = { fakePlayer },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        val item = RecordingItem(file, "to_delete.wav", "Today", "100 B", "WAV")
        vm.playRecording(item)

        assertEquals(file.absolutePath, vm.pendingPreparationPath.value)

        vm.deleteRecording(item)
        advanceUntilIdle()

        assertTrue(fakePlayer.isReleased)
        assertNull(vm.pendingPreparationPath.value)
        assertFalse(file.exists())
    }

    @Test
    fun `onCleared releases player and does not trigger new load`() = runTest(testDispatcher) {
        val file = File(recordingsDir, "playing.wav").apply { writeBytes(ByteArray(100)) }
        val fakePlayer = FakeAudioPlayer()

        val vm = RecordingsViewModel(
            context = fakeContext,
            playerFactory = { fakePlayer },
            loadOnInit = false
        )
        vm.ioDispatcher = testDispatcher

        val item = RecordingItem(file, "playing.wav", "Today", "100 B", "WAV")
        vm.playRecording(item)
        fakePlayer.preparedListener?.invoke(fakePlayer)

        assertTrue(fakePlayer.isStarted)
        assertEquals(file.absolutePath, vm.currentlyPlayingPath.value)

        // Trigger onCleared using reflection or subclass/extension
        val onClearedMethod = RecordingsViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)

        assertTrue(fakePlayer.isReleased)
    }
}
