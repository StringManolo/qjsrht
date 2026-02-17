package com.stringmanolo.qjsrht

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private var logThread: Thread? = null
    private var isRunning = true
    private lateinit var config: JSONObject

    private lateinit var btnTestQjs: Button
    private lateinit var btnTestTor: Button
    private lateinit var btnClear: Button
    private lateinit var btnRestart: Button
    private lateinit var btnStop: Button

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
        btnTestQjs = findViewById(R.id.btnTestQjs)
        btnTestTor = findViewById(R.id.btnTestTor)
        btnClear = findViewById(R.id.btnClear)
        btnRestart = findViewById(R.id.btnRestart)
        btnStop = findViewById(R.id.btnStop)

        startServerService()
        startLogReader()

        btnTestQjs.setOnClickListener { runTest("qjs", "--version") }
        btnTestTor.setOnClickListener { runTest("tor", "--version") }
        btnClear.setOnClickListener {
            logTextView.text = ""
            appendLog("Logs cleared")
        }
        btnRestart.setOnClickListener {
            appendLog("Restarting server...")
            stopServerService()
            Thread.sleep(1000)
            startServerService()
        }
        btnStop.setOnClickListener {
            appendLog("Stopping server...")
            stopServerService()
        }
    }

    private fun loadConfig() {
        val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
        config = JSONObject(jsonString)
    }

    private fun startServerService() {
        val intent = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServerService() {
        stopService(Intent(this, ServerService::class.java))
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

    private fun runTest(command: String, arg: String) {
        Thread {
            try {
                val appDir = filesDir
                val fullPath = File(appDir, command).absolutePath
                val process = ProcessBuilder(fullPath, arg)
                    .directory(appDir)
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                val exitCode = process.waitFor()
                runOnUiThread {
                    appendLog("--- $command $arg ---")
                    appendLog("Exit code: $exitCode")
                    appendLog("Output:\n$output")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("Error running $command: ${e.message}")
                }
            }
        }.start()
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            logTextView.append("$text\n")
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        logThread?.interrupt()
    }
}
