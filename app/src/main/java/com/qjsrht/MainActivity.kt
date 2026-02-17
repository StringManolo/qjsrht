package com.qjsrht

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private var logThread: Thread? = null
    private var isRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If production mode, don't show UI
        if (BuildConfig.MODE == "production") {
            // Start service and finish activity
            startServerService()
            finish()
            return
        }

        // Debug mode - show logs UI
        setContentView(R.layout.activity_main)
        
        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.scrollView)

        // Start the server service
        startServerService()

        // Start reading logs
        startLogReader()
    }

    private fun startServerService() {
        val serviceIntent = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startLogReader() {
        logThread = Thread {
            try {
                val process = Runtime.getRuntime().exec("logcat -v time ServerService:V *:S")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                var line: String?
                while (isRunning && reader.readLine().also { line = it } != null) {
                    runOnUiThread {
                        logTextView.append(line + "\n")
                        scrollView.post {
                            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logTextView.append("Error reading logs: ${e.message}\n")
                }
            }
        }
        logThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        logThread?.interrupt()
    }
}
