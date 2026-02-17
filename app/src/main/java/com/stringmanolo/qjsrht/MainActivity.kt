package com.stringmanolo.qjsrht

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.*

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var config: JSONObject

    // Botones
    private lateinit var btnTestQjs: Button
    private lateinit var btnTestTor: Button
    private lateinit var btnCheckFiles: Button
    private lateinit var btnClear: Button
    private lateinit var btnRestart: Button
    private lateinit var btnStop: Button

    private val handler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()

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
        btnCheckFiles = findViewById(R.id.btnCheckFiles)
        btnClear = findViewById(R.id.btnClear)
        btnRestart = findViewById(R.id.btnRestart)
        btnStop = findViewById(R.id.btnStop)

        // Conectar listener del servicio
        ServerService.logListener = { msg ->
            appendLog(msg)
        }

        startServerService()

        // Log inicial
        appendLog("MainActivity started, waiting for service logs...")

        btnTestQjs.setOnClickListener { runBinaryTest("qjs", "--version") }
        btnTestTor.setOnClickListener { runBinaryTest("tor", "--version") }
        btnCheckFiles.setOnClickListener { checkFiles() }
        btnClear.setOnClickListener {
            logBuffer.clear()
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

    private fun appendLog(text: String) {
        handler.post {
            logBuffer.append(text).append("\n")
            logTextView.text = logBuffer.toString()
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun runBinaryTest(binary: String, arg: String) {
        Thread {
            try {
                val appDir = filesDir
                val binaryFile = File(appDir, binary)
                if (!binaryFile.exists()) {
                    appendLog("ERROR: $binary not found")
                    return@Thread
                }
                if (!binaryFile.canExecute()) {
                    appendLog("ERROR: $binary not executable")
                    return@Thread
                }
                appendLog("Running: $binary $arg")
                val process = ProcessBuilder(binaryFile.absolutePath, arg)
                    .directory(appDir)
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    appendLog("[$binary] $line")
                }
                val exitCode = process.waitFor()
                appendLog("Exit code: $exitCode")
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
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerService.logListener = null
    }
}
