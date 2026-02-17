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
import java.io.*
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private var logUpdater: Timer? = null
    private lateinit var config: JSONObject
    private lateinit var logFile: File

    // Botones
    private lateinit var btnTestQjs: Button
    private lateinit var btnTestTor: Button
    private lateinit var btnCheckFiles: Button
    private lateinit var btnRefreshLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnRestart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logFile = File(filesDir, "logs.txt")

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
        btnCheckFiles = findViewById(R.id.btnCheckFiles)
        btnRefreshLogs = findViewById(R.id.btnRefreshLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnRestart = findViewById(R.id.btnRestart)
        btnStop = findViewById(R.id.btnStop)

        startServerService()

        // Iniciar actualización automática de logs cada segundo
        startLogUpdater()

        btnTestQjs.setOnClickListener { runBinaryTest("qjs", "--version") }
        btnTestTor.setOnClickListener { runBinaryTest("tor", "--version") }
        btnCheckFiles.setOnClickListener { checkFiles() }
        btnRefreshLogs.setOnClickListener { refreshLogs() }
        btnClearLogs.setOnClickListener {
            logFile.writeText("")
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
        try {
            val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
            config = JSONObject(jsonString)
        } catch (e: Exception) {
            config = JSONObject()
        }
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

    private fun startLogUpdater() {
        logUpdater = Timer()
        logUpdater?.schedule(object : TimerTask() {
            override fun run() {
                runOnUiThread { refreshLogs() }
            }
        }, 0, 1000) // cada 1 segundo
    }

    private fun refreshLogs() {
        try {
            if (logFile.exists()) {
                val content = logFile.readText()
                logTextView.text = content
                scrollView.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
            } else {
                logTextView.text = "(Log file not created yet)"
            }
        } catch (e: Exception) {
            logTextView.text = "Error reading logs: ${e.message}"
        }
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            logTextView.append("$text\n")
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
        // También escribir al archivo
        try {
            logFile.appendText("$text\n")
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun runBinaryTest(binary: String, arg: String) {
        Thread {
            try {
                val appDir = filesDir
                val binaryFile = File(appDir, binary)
                if (!binaryFile.exists()) {
                    appendLog("ERROR: $binary not found in app directory")
                    return@Thread
                }
                if (!binaryFile.canExecute()) {
                    appendLog("ERROR: $binary is not executable")
                    return@Thread
                }
                val fullPath = binaryFile.absolutePath
                appendLog("Running: $fullPath $arg")
                val process = ProcessBuilder(fullPath, arg)
                    .directory(appDir)
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                    // También mostrar en tiempo real
                    appendLog("[${binary}] $line")
                }
                val exitCode = process.waitFor()
                appendLog("Exit code: $exitCode")
                if (output.isNotEmpty()) {
                    // appendLog("Output:\n$output")
                }
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
            }
        }.start()
    }

    private fun checkFiles() {
        Thread {
            val appDir = filesDir
            appendLog("--- Files in ${appDir.absolutePath} ---")
            appDir.listFiles()?.forEach {
                val exec = if (it.canExecute()) "x" else "-"
                val read = if (it.canRead()) "r" else "-"
                val write = if (it.canWrite()) "w" else "-"
                appendLog("${it.name} ($read$write$exec) size: ${it.length()} bytes")
            }
            // También listar assets
            try {
                assets.list("")?.forEach { asset ->
                    appendLog("Asset root: $asset")
                }
            } catch (e: Exception) {
                appendLog("Error listing assets: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        logUpdater?.cancel()
    }
}
