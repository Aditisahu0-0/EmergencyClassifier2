package com.example.emergencyclassifier

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CallMonitorHelper(private val context: Context) {

    companion object {
        private const val TAG = "CallMonitorHelper"

        // List all required permissions here
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private var callback: CallMonitorCallback? = null
    private val callReceiver = CallBroadcastReceiver()

    interface CallMonitorCallback {
        fun onCallStateChanged(state: Int)
        fun onEmergencyScoreUpdated(score: Float, isHighEmergency: Boolean)
    }

    fun registerCallback(callback: CallMonitorCallback) {
        this.callback = callback
        registerCallReceiver()
        Log.d(TAG, "Registered callback and broadcast receiver")
    }

    fun unregisterCallback() {
        unregisterCallReceiver()
        this.callback = null
        Log.d(TAG, "Unregistered broadcast receiver and cleared callback")
    }

    fun startMonitoring() {
        Log.d(TAG, "Starting call monitoring")
        val intent = Intent(context, CallMonitorService::class.java).apply {
            action = CallMonitorService.ACTION_START_MONITORING
        }
        context.startService(intent)
    }

    fun stopMonitoring() {
        Log.d(TAG, "Stopping call monitoring")
        val intent = Intent(context, CallMonitorService::class.java).apply {
            action = CallMonitorService.ACTION_STOP_MONITORING
        }
        context.startService(intent)
    }

    fun arePermissionsGranted(): Boolean {
        // Check each required permission
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission not granted: $permission")
                return false
            }
        }
        Log.d(TAG, "All permissions are granted")
        return true
    }

    fun requestPermissions(activity: Activity, requestCode: Int) {
        // Check which permissions we need to request
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(activity, permissionsToRequest, requestCode)
        } else {
            Log.d(TAG, "No permissions to request - all already granted")
        }
    }

    private fun registerCallReceiver() {
        val filter = IntentFilter(CallMonitorService.ACTION_CALL_STATE_CHANGED)

        // Make sure the filter includes the action
        Log.d(TAG, "Registering receiver for action: ${CallMonitorService.ACTION_CALL_STATE_CHANGED}")

        try {
            context.unregisterReceiver(callReceiver) // Unregister first to avoid duplicates
        } catch (e: IllegalArgumentException) {
            // Ignore if not registered
        }

        // Use the appropriate method based on API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(callReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(callReceiver, filter)
        }
    }

    private fun unregisterCallReceiver() {
        try {
            context.unregisterReceiver(callReceiver)
            Log.d(TAG, "Unregistered call receiver")
        } catch (e: IllegalArgumentException) {
            // Receiver not registered, ignore
            Log.d(TAG, "Receiver was not registered, ignoring unregister call")
        }
    }

    private inner class CallBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Received broadcast: ${intent.action}")

            if (intent.action == CallMonitorService.ACTION_CALL_STATE_CHANGED) {
                val callState = intent.getIntExtra(CallMonitorService.EXTRA_CALL_STATE, -1)

                // Check if the broadcast contains emergency score
                val hasEmergencyScore = intent.hasExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE)
                val emergencyScore = intent.getFloatExtra(CallMonitorService.EXTRA_EMERGENCY_SCORE, 0f)
                val isHighEmergency = intent.getBooleanExtra(CallMonitorService.EXTRA_IS_HIGH_EMERGENCY, false)

                Log.d(TAG, "📱 Broadcast data: callState=$callState, " +
                        "hasEmergencyScore=$hasEmergencyScore, " +
                        "emergencyScore=$emergencyScore, " +
                        "isHighEmergency=$isHighEmergency")

                if (callState != -1) {
                    callback?.onCallStateChanged(callState)
                    Log.d(TAG, "Called onCallStateChanged with state $callState")
                }

                if (hasEmergencyScore) {
                    callback?.onEmergencyScoreUpdated(emergencyScore, isHighEmergency)
                    Log.d(TAG, "🚨 Called onEmergencyScoreUpdated with score $emergencyScore")
                }
            }
        }
    }
}