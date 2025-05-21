package com.example.emergencyclassifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), CallMonitorHelper.CallMonitorCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 123
    }

    private lateinit var btnStartStop: Button
    private lateinit var tvCallState: TextView
    private lateinit var tvEmergencyScore: TextView
    private lateinit var tvEmergencyStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var emergencyIndicator: View

    private lateinit var callMonitorHelper: CallMonitorHelper
    private var isMonitoring = false
    private var isTestModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "MainActivity created")

        // Initialize UI components
        initViews()

        // Setup call monitor
        callMonitorHelper = CallMonitorHelper(this)

        // Check permissions
        if (!callMonitorHelper.arePermissionsGranted()) {
            callMonitorHelper.requestPermissions(this, PERMISSION_REQUEST_CODE)
        }

        // Setup buttons
        setupButtons()

        // Setup broadcast receiver
        setupCallMonitor()
    }

    private fun initViews() {
        btnStartStop = findViewById(R.id.btnStartStop)
        tvCallState = findViewById(R.id.tvCallState)
        tvEmergencyScore = findViewById(R.id.tvEmergencyScore)
        tvEmergencyStatus = findViewById(R.id.tvEmergencyStatus)
        tvLog = findViewById(R.id.tvLog)
        emergencyIndicator = findViewById(R.id.emergencyIndicator)
    }

    private fun setupButtons() {
        // Start/Stop button
        btnStartStop.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                // Clear previous scores when starting fresh
                tvEmergencyScore.text = getString(R.string.emergency_score_format, "0.00")
                tvEmergencyStatus.text = getString(R.string.emergency_status_format, "Normal")
                emergencyIndicator.setBackgroundColor(Color.GREEN)
                startMonitoring()

                // Add this to trigger an immediate demo score:
                Handler(Looper.getMainLooper()).postDelayed({
                    val testIntent = Intent(CallMonitorService.ACTION_CALL_STATE_CHANGED).apply {
                        putExtra(CallMonitorService.EXTRA_CALL_STATE, TelephonyManager.CALL_STATE_IDLE)
                        putExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE, 0.2f)
                        putExtra(CallMonitorService.EXTRA_IS_HIGH_EMERGENCY, false)
                    }
                    sendBroadcast(testIntent)
                    appendToLog("🔄 Monitoring active, waiting for scores...")
                }, 500)
            }
        }

        // Test button - FIXED
        val btnTestEmergency: Button = findViewById(R.id.btnTestEmergency)
        btnTestEmergency.setOnClickListener {
            isTestModeActive = true
            appendToLog("📊 TEST: Simulating emergency detection")

            // Send a direct broadcast to yourself (no service needed)
            val testIntent = Intent(CallMonitorService.ACTION_CALL_STATE_CHANGED).apply {
                putExtra(CallMonitorService.EXTRA_CALL_STATE, TelephonyManager.CALL_STATE_OFFHOOK)
                putExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE, 0.8f) // High score for testing
                putExtra(CallMonitorService.EXTRA_IS_HIGH_EMERGENCY, true)
            }
            sendBroadcast(testIntent)

            // Start a coroutine to simulate score changes
            lifecycleScope.launch {
                repeat(20) { i ->
                    delay(1000) // 1 second between updates
                    val score = 0.5f + (i.toFloat() / 50.0f) // Gradually increasing score
                    val isHigh = score > 0.7f

                    // Update UI directly
                    onEmergencyScoreUpdated(score, isHigh)

                    // Also broadcast it for completeness
                    val updateIntent = Intent(CallMonitorService.ACTION_CALL_STATE_CHANGED).apply {
                        putExtra(CallMonitorService.EXTRA_CALL_STATE, TelephonyManager.CALL_STATE_OFFHOOK)
                        putExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE, score)
                        putExtra(CallMonitorService.EXTRA_IS_HIGH_EMERGENCY, isHigh)
                    }
                    sendBroadcast(updateIntent)
                }
                isTestModeActive = false
            }
        }
    }

    private fun startMonitoring() {
        if (!callMonitorHelper.arePermissionsGranted()) {
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
            callMonitorHelper.requestPermissions(this, PERMISSION_REQUEST_CODE)
            return
        }

        // Register callback
        callMonitorHelper.registerCallback(this)

        // Start monitoring service
        callMonitorHelper.startMonitoring()

        // Update UI
        isMonitoring = true
        btnStartStop.text = getString(R.string.stop_monitoring)
        btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)

        // Log
        appendToLog("Started monitoring calls")
    }

    private fun stopMonitoring() {
        // Unregister callback
        callMonitorHelper.unregisterCallback()

        // Stop monitoring service
        callMonitorHelper.stopMonitoring()

        // Update UI
        isMonitoring = false
        btnStartStop.text = getString(R.string.start_monitoring)
        btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_light)

        // Log
        appendToLog("Stopped monitoring calls")
    }

    override fun onCallStateChanged(state: Int) {
        val stateText = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "No Call (IDLE)"
            TelephonyManager.CALL_STATE_RINGING -> "Ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "Active Call (OFFHOOK)"
            else -> "Unknown"
        }

        runOnUiThread {
            tvCallState.text = getString(R.string.call_state_format, stateText)
            appendToLog("Call state changed to: $stateText")
        }
    }

    override fun onEmergencyScoreUpdated(score: Float, isHighEmergency: Boolean) {
        runOnUiThread {
            Log.d(TAG, "⚠️ UI UPDATE: Score=$score, High=$isHighEmergency")

            val scoreText = String.format("%.2f", score)
            val statusText = if (isHighEmergency) {
                "HIGH EMERGENCY!"
            } else {
                "Normal"
            }

            // Update text views with emergency data
            tvEmergencyScore.text = getString(R.string.emergency_score_format, scoreText)
            tvEmergencyStatus.text = getString(R.string.emergency_status_format, statusText)

            // Change background color based on emergency level
            val color = when {
                isHighEmergency -> Color.RED
                score > 0.4f -> Color.YELLOW
                else -> Color.GREEN
            }
            emergencyIndicator.setBackgroundColor(color)

            // Log the update with emoji for visibility
            val logMessage = if (isHighEmergency) {
                "🚨 HIGH EMERGENCY! Score: $scoreText"
            } else {
                "📊 Emergency score: $scoreText (normal)"
            }
            appendToLog(logMessage)
        }
    }

    private fun appendToLog(message: String) {
        // Make sure view is initialized
        if (!::tvLog.isInitialized) {
            Log.d(TAG, "Cannot append to log: $message")
            return
        }

        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        val formattedMessage = "[$timestamp] $message\n"

        runOnUiThread {
            tvLog.append(formattedMessage)

            // Scroll to the bottom
            val scrollAmount = tvLog.layout?.getLineTop(tvLog.lineCount) ?: 0
            if ((scrollAmount > tvLog.height) && (tvLog.scrollY != scrollAmount - tvLog.height)) {
                tvLog.scrollTo(0, scrollAmount - tvLog.height)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupCallMonitor() // Register to receive broadcasts directly
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(directBroadcastReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (callMonitorHelper.arePermissionsGranted()) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val directBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "🔔 MainActivity received broadcast: ${intent.action}")

            if (intent.action == CallMonitorService.ACTION_CALL_STATE_CHANGED) {
                val state = intent.getIntExtra(CallMonitorService.EXTRA_CALL_STATE, -1)

                if (state != -1) {
                    onCallStateChanged(state)
                }

                if (intent.hasExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE)) {
                    val score = intent.getFloatExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE, 0f)
                    val isHigh = intent.getBooleanExtra(CallMonitorService.EXTRA_IS_HIGH_EMERGENCY, false)
                    onEmergencyScoreUpdated(score, isHigh)
                }
            }
        }
    }

    private fun setupCallMonitor() {
        // Make sure we ALWAYS register to receive broadcasts, even if service isn't started
        val filter = IntentFilter(CallMonitorService.ACTION_CALL_STATE_CHANGED)
        try {
            unregisterReceiver(directBroadcastReceiver) // Avoid duplicate registrations
        } catch (e: Exception) {
            // Ignore if not registered
        }

        // Add the RECEIVER_NOT_EXPORTED flag for Android 13+ compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(directBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(directBroadcastReceiver, filter)
        }

        Log.d(TAG, "✅ BroadcastReceiver registered for ACTION_CALL_STATE_CHANGED")
    }
}