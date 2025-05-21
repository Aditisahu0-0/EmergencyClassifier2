package com.example.emergencyclassifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlin.math.sqrt

class AudioFeatureExtractor(private val context: Context) {
    private val tag = "AudioFeatureExtractor"

    private var audioRecord: AudioRecord? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 3
    private var isRecording = false

    fun initRecorder(): Boolean {
        // Check permissions first
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "Cannot initialize recorder - RECORD_AUDIO permission not granted")
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            Log.d(tag, "Audio recorder initialized with buffer size: $bufferSize")
            true // Return moved outside try block
        } catch (e: SecurityException) {
            Log.e(tag, "Security exception initializing audio recorder: ${e.message}", e)
            false // Return moved outside try block
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize audio recorder: ${e.message}", e)
            false // Return moved outside try block
        }
    }

    fun startRecording(): Boolean {
        // Check permission again in case it was revoked
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "Cannot start recording - RECORD_AUDIO permission not granted")
            return false
        }

        if (audioRecord == null) {
            Log.e(tag, "Cannot start recording - recorder not initialized")
            return false
        }

        return try {
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "Cannot start recording - recorder not in initialized state")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d(tag, "Audio recording started successfully")
            true
        } catch (e: SecurityException) {
            Log.e(tag, "Security exception starting recording: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording: ${e.message}", e)
            false
        }
    }

    fun stopRecording() {
        try {
            if (isRecording && audioRecord != null) {
                audioRecord?.stop()
                isRecording = false
                Log.d(tag, "Audio recording stopped")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping recording: ${e.message}", e)
        }
    }

    fun extractFeatures(): FloatArray {
        if (!isRecording || audioRecord == null) {
            Log.e(tag, "Cannot extract features - recording not active")
            return FloatArray(0)
        }

        // Check permission again in case it was revoked during recording
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "Cannot extract features - RECORD_AUDIO permission not granted")
            return FloatArray(0)
        }

        return try {
            val buffer = ShortArray(bufferSize / 2)
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (read <= 0) {
                Log.w(tag, "No audio data read (read=$read)")
                return FloatArray(0)
            }

            Log.d(tag, "Read $read audio samples")

            // Simple feature extraction: convert to floating point and normalize
            val features = buffer.take(read).map { it / Short.MAX_VALUE.toFloat() }.toFloatArray()

            // Calculate RMS using Kotlin idiomatic code
            val rms = features.map { it * it }.average().let { sqrt(it) }.toFloat()
            Log.d(tag, "Audio RMS: $rms (should be > 0.01 if capturing actual audio)")

            features
        } catch (e: SecurityException) {
            Log.e(tag, "Security exception extracting features: ${e.message}", e)
            FloatArray(0)
        } catch (e: Exception) {
            Log.e(tag, "Error extracting features: ${e.message}", e)
            FloatArray(0)
        }
    }

    fun release() {
        try {
            stopRecording()
            audioRecord?.release()
            audioRecord = null
            Log.d(tag, "Audio recorder released")
        } catch (e: Exception) {
            Log.e(tag, "Error releasing audio recorder: ${e.message}", e)
        }
    }
}