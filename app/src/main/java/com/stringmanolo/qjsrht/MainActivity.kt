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
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnTestQjs: Button
    private lateinit var btnTestTor: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnRestartServer: Button
    private lateinit var btnStopServer: Button

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
        btnTestQjs = findViewById(R.id.btnTestQjs)
        btnTestTor = findViewById(R.id.btnTestTor)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnRestartServer = findViewById(R.id.btnRestartServer)
        btnStopServer = findViewById(R.id.btnStopServer)

        // Hacer el texto seleccionable
        logTextView.setTextIsSelectable(true)

        // Botones
        btnTestQjs.setOnClickListener { runTest("qjs") }
        btnTestTor.setOnClickListener { runTest("tor") }
        btnClearLogs.setOnClickListener { logTextView.text = "" }
        btnRestartServer.setOnClickListener {
            stopServerService()
            Thread.sleep(1000)
            startServerService()
        }
        btnStopServer.setOnClickListener { stopServerService() }

        // Iniciar el servicio y lector de logs
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
        appendLog("Servicio iniciado")
    }

    private fun stopServerService() {
        val serviceIntent = Intent(this, ServerService::class.java)
        stopService(serviceIntent)
        appendLog("Servicio detenido")
    }

    private fun runTest(binary: String) {
        Thread {
            try {
                val appDir = filesDir
                val binaryPath = File(appDir, binary).absolutePath
                val process = ProcessBuilder(binaryPath, "--version")
                    .directory(appDir)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val output = line
                    runOnUiThread {
                        appendLog("[$binary] $output")
                    }
                }
                val exitCode = process.waitFor()
                runOnUiThread {
                    appendLog("[$binary] exit code: $exitCode")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("Error running $binary: ${e.message}")
                }
            }
        }.start()
    }

    private fun startLogReader() {
        logThread = Thread {
            try {
                val process = Runtime.getRuntime().exec("logcat -v time ServerService:V MainActivity:V *:S")
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
