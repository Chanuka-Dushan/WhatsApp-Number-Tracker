package com.example.whatsapp_monitor

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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
import io.flutter.plugin.common.MethodChannel

class FloatingButtonService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        var channel: MethodChannel? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("FloatingButton", "Service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingButton()
    }

    private fun setupFloatingButton() {
        Log.d("FloatingButton", "Setting up floating button")
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_button_layout, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 100
        }

        val startStopButton = floatView.findViewById<Button>(R.id.start_stop_button)
        startStopButton.text = if (WhatsAppMonitorService.isMonitoringActive()) "Stop" else "Start"
        startStopButton.setOnClickListener {
            Log.d("FloatingButton", "Button clicked")
            toggleMonitoring()
        }

        Log.d("FloatingButton", "Adding view to window manager")
        windowManager.addView(floatView, params)

        floatView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun toggleMonitoring() {
        if (WhatsAppMonitorService.isMonitoringActive()) {
            Log.d("FloatingButton", "Stopping monitoring")
            handler.post {
                channel?.invokeMethod("stopMonitoring", null)
                floatView.findViewById<Button>(R.id.start_stop_button).text = "Start"
                Toast.makeText(this, "Monitoring Stopped", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("FloatingButton", "Requesting to start monitoring")
            handler.post {
                channel?.invokeMethod("requestStartMonitoring", null)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FloatingButton", "Service destroyed")
        if (::floatView.isInitialized) {
            windowManager.removeView(floatView)
        }
    }
}