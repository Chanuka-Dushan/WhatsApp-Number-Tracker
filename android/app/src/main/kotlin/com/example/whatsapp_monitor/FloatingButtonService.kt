package com.example.whatsapp_monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.MethodChannel
import android.content.pm.ServiceInfo

class FloatingButtonService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    companion object {
        var channel: MethodChannel? = null
        var isRunning = false
        private const val TAG = "FloatingButtonService"
        private const val NOTIFICATION_CHANNEL_ID = "whatsapp_monitor_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.example.whatsapp_monitor.START"
        private const val ACTION_STOP = "com.example.whatsapp_monitor.STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        isRunning = true
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            setupNotificationChannel()
            val notification = createNotification(WhatsAppMonitorService.isMonitoringActive())
            Log.d(TAG, "Starting foreground service with notification")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            setupFloatingButton()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START -> {
                    if (!WhatsAppMonitorService.isMonitoringActive()) {
                        val button = floatView?.findViewById<Button>(R.id.start_stop_button)
                        button?.let { toggleMonitoring(it, true) }
                    }
                }
                ACTION_STOP -> {
                    if (WhatsAppMonitorService.isMonitoringActive()) {
                        val button = floatView?.findViewById<Button>(R.id.start_stop_button)
                        button?.let { toggleMonitoring(it, false) }
                    }
                }
                "UPDATE_STATE" -> {
                    if (floatView != null) {
                        val button = floatView!!.findViewById<Button>(R.id.start_stop_button)
                        updateButtonState(button)
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            createNotification(WhatsAppMonitorService.isMonitoringActive())
                        )
                    }
                }
                "HIDE_FLOATING_BUTTON" -> {
                    hideFloatingButton()
                }
                "SHOW_FLOATING_BUTTON" -> {
                    if (floatView == null) {
                        setupFloatingButton()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WhatsApp Monitor Controls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Controls for WhatsApp Monitor"
                enableLights(true)
                enableVibration(true)
            }
            Log.d(TAG, "Creating notification channel: $NOTIFICATION_CHANNEL_ID")
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(isMonitoring: Boolean): android.app.Notification {
        val startIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_START
        }
        val stopIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_STOP
        }

        val startPendingIntent = PendingIntent.getService(
            this, 0, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WhatsApp Monitor")
            .setContentText(if (isMonitoring) "Monitoring active" else "Monitoring stopped")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)

        if (!isMonitoring) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "Start",
                    startPendingIntent
                )
            )
        } else {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Stop",
                    stopPendingIntent
                )
            )
        }

        Log.d(TAG, "Notification created with state: $isMonitoring")
        return builder.build()
    }

    private fun updateNotification() {
        try {
            notificationManager.notify(
                NOTIFICATION_ID,
                createNotification(WhatsAppMonitorService.isMonitoringActive())
            )
            Log.d(TAG, "Notification updated")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}", e)
        }
    }

    private fun setupFloatingButton() {
        try {
            Log.d(TAG, "Setting up floating button")
            floatView = LayoutInflater.from(this).inflate(R.layout.floating_button_layout, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 0
                y = 100
            }

            val startStopButton = floatView!!.findViewById<Button>(R.id.start_stop_button)
            updateButtonState(startStopButton)

            startStopButton.setOnClickListener {
                Log.d(TAG, "Floating button clicked")
                toggleMonitoring(startStopButton, !WhatsAppMonitorService.isMonitoringActive())
            }

            windowManager.addView(floatView, params)
            Log.d(TAG, "Floating button view added")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up floating button: ${e.message}", e)
            stopSelf()
        }
    }

    private fun hideFloatingButton() {
        try {
            if (floatView != null) {
                windowManager.removeView(floatView)
                floatView = null
                Log.d(TAG, "Floating button hidden")
            }
            if (!WhatsAppMonitorService.isMonitoringActive()) {
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding floating button: ${e.message}", e)
        }
    }

    private fun updateButtonState(button: Button) {
        handler.post {
            button.text = if (WhatsAppMonitorService.isMonitoringActive()) "Stop" else "Start"
            button.setBackgroundColor(
                if (WhatsAppMonitorService.isMonitoringActive())
                    resources.getColor(android.R.color.holo_red_light)
                else
                    resources.getColor(android.R.color.holo_green_light)
            )
            updateNotification()
        }
    }

    private fun toggleMonitoring(button: Button, start: Boolean) {
        button.isClickable = false

        handler.post {
            try {
                if (start) {
                    Log.d(TAG, "Starting monitoring via floating button/notification")
                    WhatsAppMonitorService.startMonitoring("", this@FloatingButtonService)
                    channel?.invokeMethod("startMonitoring", null, object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            handler.post {
                                Toast.makeText(this@FloatingButtonService,
                                    "Monitoring Started", Toast.LENGTH_SHORT).show()
                                updateButtonState(button)
                                button.isClickable = true
                                updateNotification()
                            }
                        }

                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                            handler.post {
                                Toast.makeText(this@FloatingButtonService,
                                    "Failed to start monitoring: $errorMessage", Toast.LENGTH_SHORT).show()
                                button.isClickable = true
                            }
                        }

                        override fun notImplemented() {
                            handler.post {
                                button.isClickable = true
                            }
                        }
                    })
                } else {
                    Log.d(TAG, "Stopping monitoring via floating button/notification")
                    WhatsAppMonitorService.stopMonitoring(this@FloatingButtonService)
                    channel?.invokeMethod("stopMonitoring", null, object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            handler.post {
                                Toast.makeText(this@FloatingButtonService,
                                    "Monitoring Stopped", Toast.LENGTH_SHORT).show()
                                updateButtonState(button)
                                button.isClickable = true
                                updateNotification()
                            }
                        }

                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                            handler.post {
                                Toast.makeText(this@FloatingButtonService,
                                    "Failed to stop monitoring: $errorMessage", Toast.LENGTH_SHORT).show()
                                button.isClickable = true
                            }
                        }

                        override fun notImplemented() {
                            handler.post {
                                button.isClickable = true
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling monitoring: ${e.message}", e)
                handler.post {
                    Toast.makeText(this@FloatingButtonService,
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    button.isClickable = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        isRunning = false
        notificationManager.cancel(NOTIFICATION_ID)
        try {
            if (floatView != null) {
                windowManager.removeView(floatView)
                floatView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating button: ${e.message}", e)
        }
    }
}