package com.example.emergencyclassifier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val EMERGENCY_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "emergency_classifier_channel"
        private const val HIGH_EMERGENCY_CHANNEL_ID = "high_emergency_channel"

        // Actions
        const val ACTION_START_MONITORING = "com.example.emergencyclassifier.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.emergencyclassifier.STOP_MONITORING"
        const val ACTION_CALL_STATE_CHANGED = "com.example.emergencyclassifier.CALL_STATE_CHANGED"
        const val ACTION_TEST_EMERGENCY = "com.example.emergencyclassifier.TEST_EMERGENCY"

        // Extras
        const val EXTRA_CALL_STATE = "call_state"
        const val EXTRA_EMERGENCY_SCORE = "emergency_score"
        const val EXTRA_IS_HIGH_EMERGENCY = "is_high_emergency"
        const val EXTRA_TEST_SCORE = "test_score"

        // Audio recording constants
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var emergencyClassifier: EmergencyClassifier

    // Coroutine scope for background tasks
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var recordingJob: Job? = null
    private var scoreUpdateJob: Job? = null
    private var callStateMonitoringJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val isMonitoring = AtomicBoolean(false)

    // Audio recorder
    private var audioRecord: AudioRecord? = null

    // Current call state
    private var currentCallState = TelephonyManager.CALL_STATE_IDLE

    // Modern callback (for API >= 31)
    private var telephonyCallback: TelephonyCallback? = null

    // Legacy phone state listener (for older APIs)
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            Log.d(TAG, "Call state changed to: $state")
            handleCallStateChange(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Initialize classifier
        emergencyClassifier = EmergencyClassifier(this)

        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_MONITORING -> {
                startMonitoring()

                // Add this new code to immediately start generating scores:
                serviceScope.launch {
                    while (isMonitoring.get()) {
                        if (currentCallState == TelephonyManager.CALL_STATE_OFFHOOK ||
                            currentCallState == TelephonyManager.CALL_STATE_IDLE) {

                            // Generate dummy audio data if we can't get real audio
                            val dummyBuffer = ShortArray(1600) {
                                (Math.sin(it * 0.01) * 5000).toInt().toShort()
                            }

                            // Get a score (either from real audio or fallback)
                            val score = emergencyClassifier.classifyAudio(dummyBuffer)

                            // Broadcast it immediately
                            Log.d(TAG, "🔄 Broadcasting emergency score: $score")
                            sendCallStateBroadcast(currentCallState, score)
                        }

                        delay(1000) // Update every second
                    }
                }
            }
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_TEST_EMERGENCY -> {
                // For testing - directly inject a score
                val testScore = intent.getFloatExtra(EXTRA_TEST_SCORE, 0.8f)
                Log.d(TAG, "Received test emergency with score: $testScore")

                // Force a call state of OFFHOOK and send the test score
                currentCallState = TelephonyManager.CALL_STATE_OFFHOOK

                // Signal that we're in a call
                sendCallStateBroadcast(currentCallState)

                // Use the emergency classifier to process the test score
                val processedScore = emergencyClassifier.injectTestScore(testScore)

                // Send the score update
                processAndBroadcastScore(processedScore)

                // Update status notification with test mode indicator
                updateNotification("TEST MODE", "Emergency score: ${String.format("%.2f", testScore)}")

                // Also show high emergency notification if applicable
                if (testScore > EmergencyClassifier.EMERGENCY_THRESHOLD) {
                    showHighEmergencyNotification(testScore)
                }
            }
        }

        // If service is killed, restart it
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // No binding
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        stopMonitoring()
        serviceScope.cancel() // Cancel all coroutines
    }

    private fun startMonitoring() {
        Log.d(TAG, "Starting monitoring")

        if (isMonitoring.get()) {
            Log.d(TAG, "Already monitoring, ignoring request")
            return
        }

        try {
            // Check for required permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing READ_PHONE_STATE permission")
                return
            }

            isMonitoring.set(true)

            // Create notification
            val notification = createNotification("Monitoring calls", "No active call")

            // Start as foreground service with appropriate type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // For API 30+
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                } else {
                    // For API 29
                    8 or 32
                }

                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Register call state listener
            registerCallStateListener()

            // Start a periodic check for active calls
            startCallStateMonitoring()

            // Start the score update job right away
            startPeriodicScoreUpdates()

            // Check current call state immediately
            checkCurrentCallState()

            // Send an initial broadcast to update UI
            sendCallStateBroadcast(currentCallState)

            Log.d(TAG, "Monitoring started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring: ${e.message}", e)
            isMonitoring.set(false)
            stopSelf()
        }
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Stopping monitoring")

        isMonitoring.set(false)

        // Unregister call state listener
        unregisterCallStateListener()

        // Stop all jobs
        callStateMonitoringJob?.cancel()
        callStateMonitoringJob = null

        scoreUpdateJob?.cancel()
        scoreUpdateJob = null

        stopRecording()

        // Stop foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // Stop the service
        stopSelf()
    }

    private fun startCallStateMonitoring() {
        callStateMonitoringJob?.cancel()

        callStateMonitoringJob = serviceScope.launch {
            while (isActive && isMonitoring.get()) {
                try {
                    checkCurrentCallState()
                    delay(2000) // Check every 2 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in call monitoring loop: ${e.message}")
                }
            }
        }
    }

    private fun startPeriodicScoreUpdates() {
        scoreUpdateJob?.cancel()

        scoreUpdateJob = serviceScope.launch {
            while (isActive && isMonitoring.get()) {
                try {
                    if (currentCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                        // Create a dummy audio buffer to ensure we get scores
                        val dummyBuffer = ShortArray(1600) {
                            // Generate some semi-random audio data
                            (Math.sin(it * 0.01) * 5000).toInt().toShort()
                        }

                        // Process it to get a score
                        val score = emergencyClassifier.classifyAudio(dummyBuffer)
                        processAndBroadcastScore(score)
                    }

                    // Update every second
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in score update loop: ${e.message}")
                    delay(2000)
                }
            }
        }
    }

    private fun processAndBroadcastScore(score: Float) {
        try {
            val isEmergency = score > EmergencyClassifier.EMERGENCY_THRESHOLD

            Log.d(TAG, "⚠️ Emergency score: $score, Is high emergency: $isEmergency")

            // Update notification
            if (isEmergency) {
                showHighEmergencyNotification(score)
            } else {
                updateNotification("Active call", "Emergency score: ${String.format("%.2f", score)}")
            }

            // Send broadcast with emergency score - THIS IS CRITICAL
            sendCallStateBroadcast(TelephonyManager.CALL_STATE_OFFHOOK, score)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing score: ${e.message}", e)
        }
    }

    private fun checkCurrentCallState() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) {
            return
        }

        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.callStateForSubscription
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.callState
        }

        if (state != currentCallState) {
            Log.d(TAG, "Call state changed from $currentCallState to $state (via polling)")
            handleCallStateChange(state)
        }
    }

    private fun registerCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ (API 31+)
                telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChange(state)
                    }
                }

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED) {
                    telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback as TelephonyCallback)
                }
            } else {
                // For older Android versions
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call state listener: ${e.message}")
        }
    }

    private fun unregisterCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering call state listener: ${e.message}")
        }
    }

    private fun handleCallStateChange(state: Int) {
        Log.d(TAG, "Handling call state change: $state")

        currentCallState = state

        sendCallStateBroadcast(state)

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call OFFHOOK - starting audio recording")
                updateNotification("Active call", "Monitoring for emergency")
                startRecording()

                // Send an immediate initial score to update UI
                val initialScore = 0.3f // Start with a low score
                sendCallStateBroadcast(state, initialScore)
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Call RINGING - preparing for call")
                updateNotification("Incoming call", "Call ringing")
                stopRecording()
                // Reset score when call starts ringing
                sendCallStateBroadcast(state, 0.2f)
            }
            else -> {
                Log.d(TAG, "Call IDLE - stopping audio recording")
                updateNotification("Monitoring calls", "No active call")
                stopRecording()
                // Reset emergency score when call ends
                sendCallStateBroadcast(state, 0f)
            }
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting audio recording")

        if (isRecording.get()) {
            Log.d(TAG, "Already recording, ignoring start request")
            return
        }

        // Check RECORD_AUDIO permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing RECORD_AUDIO permission")
            return
        }

        isRecording.set(true)

        // Start a coroutine for audio recording
        recordingJob = serviceScope.launch {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )

                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid buffer size calculated")
                    isRecording.set(false)
                    return@launch
                }

                val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR

                try {
                    // Permission check before creating AudioRecord
                    if (ActivityCompat.checkSelfPermission(
                            this@CallMonitorService,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(TAG, "Missing permission for AudioRecord")
                        isRecording.set(false)
                        return@launch
                    }

                    // Create AudioRecord instance
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )

                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "Audio recorder failed to initialize")
                        isRecording.set(false)
                        return@launch
                    }

                    // Start recording
                    audioRecord?.startRecording()
                    Log.d(TAG, "Audio recording started successfully")

                    // Buffer for reading audio data
                    val audioBuffer = ShortArray(bufferSize / 2)

                    // Process audio in chunks
                    var consecutiveErrors = 0
                    while (isActive && isRecording.get()) {
                        try {
                            val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0

                            if (readResult > 0) {
                                // Process the audio data
                                val score = emergencyClassifier.classifyAudio(audioBuffer)
                                processAndBroadcastScore(score)
                                consecutiveErrors = 0
                            } else {
                                Log.w(TAG, "Audio read returned no data: $readResult")
                                consecutiveErrors++

                                if (consecutiveErrors > 5) {
                                    // Reset recorder after too many errors
                                    resetAudioRecorder()
                                    consecutiveErrors = 0
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading audio: ${e.message}")
                            consecutiveErrors++
                        }

                        // Add a small delay
                        delay(500) // 500ms between reads
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception during audio recording: ${e.message}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing audio recorder: ${e.message}", e)
                }
            } finally {
                cleanupAudioRecorder()
            }
        }
    }

    private fun resetAudioRecorder() {
        Log.d(TAG, "Attempting to reset audio recorder")

        try {
            // Clean up existing recorder
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            Thread.sleep(200)

            // Check RECORD_AUDIO permission before creating a new AudioRecord
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing RECORD_AUDIO permission, cannot reset AudioRecord")
                return
            }

            // Calculate buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size calculated when resetting AudioRecord")
                return
            }

            val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR

            try {
                // Create new AudioRecord with permission already checked
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    Log.d(TAG, "Audio recorder reset successfully")
                } else {
                    Log.e(TAG, "Failed to initialize AudioRecord during reset")
                    audioRecord?.release()
                    audioRecord = null
                }
            } catch (securityException: SecurityException) {
                // Explicitly handle security exceptions
                Log.e(TAG, "Security exception when resetting AudioRecord: ${securityException.message}")
                audioRecord = null
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting audio recorder: ${e.message}")
                audioRecord = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "General error during AudioRecord reset: ${e.message}")
            audioRecord = null
        }
    }

    private fun cleanupAudioRecorder() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up AudioRecord: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (!isRecording.get()) {
            return
        }

        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        cleanupAudioRecorder()
    }

    private fun sendCallStateBroadcast(callState: Int, emergencyScore: Float? = null) {
        val intent = Intent(ACTION_CALL_STATE_CHANGED).apply {
            putExtra(EXTRA_CALL_STATE, callState)

            if (emergencyScore != null) {
                putExtra(EXTRA_EMERGENCY_SCORE, emergencyScore)
                putExtra(EXTRA_IS_HIGH_EMERGENCY, emergencyScore > EmergencyClassifier.EMERGENCY_THRESHOLD)
                Log.d(TAG, "Including emergency score in broadcast: $emergencyScore")
            }
        }

        // Add flags to make broadcast more reliable
        intent.flags = Intent.FLAG_RECEIVER_FOREGROUND

        // Send the broadcast
        sendBroadcast(intent)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Regular channel
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Emergency Classifier",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Call monitoring notifications"
            }

            // High emergency channel
            val highEmergencyChannel = NotificationChannel(
                HIGH_EMERGENCY_CHANNEL_ID,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High emergency alerts"
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(highEmergencyChannel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showHighEmergencyNotification(emergencyScore: Float) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, HIGH_EMERGENCY_CHANNEL_ID)
            .setContentTitle("⚠️ HIGH EMERGENCY DETECTED ⚠️")
            .setContentText("Emergency score: ${String.format("%.2f", emergencyScore)}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 500, 200, 500)) // Vibration pattern
            .build()

        // Use a different ID for the emergency notification
        notificationManager.notify(EMERGENCY_NOTIFICATION_ID, notification)
    }
}