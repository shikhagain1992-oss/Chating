package com.example.ui.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Handles real hold-to-record AAC voice capture and audio playback for voice notes.
 */
class VoiceRecorderHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMillis: Long = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingUrl: String? = null

    fun startRecording(): Result<File> {
        stopRecording()
        return try {
            val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            currentFile = outFile
            startTimeMillis = System.currentTimeMillis()
            Result.success(outFile)
        } catch (e: Exception) {
            recorder = null
            currentFile = null
            Result.failure(e)
        }
    }

    fun stopRecording(): Pair<File?, Int> {
        val file = currentFile
        val elapsedSec = if (startTimeMillis > 0L) {
            ((System.currentTimeMillis() - startTimeMillis) / 1000L).toInt().coerceAtLeast(1)
        } else {
            0
        }
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            recorder = null
            currentFile = null
            startTimeMillis = 0L
        }
        return file to elapsedSec
    }

    fun cancelRecording() {
        val (file, _) = stopRecording()
        try {
            file?.delete()
        } catch (_: Exception) {
        }
    }

    fun playAudioUrl(
        url: String,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (url.isBlank()) {
            onError("Audio URL is empty")
            return
        }
        stopPlayback()
        try {
            val player = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { mp ->
                    mp.start()
                }
                setOnCompletionListener {
                    currentlyPlayingUrl = null
                    onCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    currentlyPlayingUrl = null
                    onError("Playback error ($what/$extra)")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
            currentlyPlayingUrl = url
        } catch (e: Exception) {
            currentlyPlayingUrl = null
            onError(e.localizedMessage ?: "Unable to play voice message")
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
            currentlyPlayingUrl = null
        }
    }

    fun isPlayingUrl(url: String): Boolean = currentlyPlayingUrl == url && mediaPlayer?.isPlaying == true
}
