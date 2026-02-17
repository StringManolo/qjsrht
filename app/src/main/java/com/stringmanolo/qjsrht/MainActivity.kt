package com.stringmanolo.qjsrht

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private var logThread: Thread? = null
    private var isRunning = true
    private lateinit var config: JSONObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadConfig()

        val mode = config.getString("mode")

        if (mode == "production") {
            startServerService()
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.scrollView)

        startServerService()
        startLogReader()
    }

    private fun loadConfig() {
        val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
        config = JSONObject(jsonString)
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

                var line = reader.readLine()
                while (isRunning && line != null) {
                    val currentLine = line
                    runOnUiThread {
                        logTextView.append("$currentLine\n")
                        scrollView.post {
                            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                    line = reader.readLine()
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
