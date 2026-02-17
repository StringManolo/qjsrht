package com.stringmanolo.qjsrht

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.*

class ServerService : Service() {
    private val TAG = "ServerService"
    private var qjsProcess: Process? = null
    private var torProcess: Process? = null
    private val CHANNEL_ID = "ServerServiceChannel"
    private lateinit var config: JSONObject
    private var mode: String = "debug"

    companion object {
        var logListener: ((String) -> Unit)? = null
        private val handler = Handler(Looper.getMainLooper())
    }

    private fun logToUI(msg: String) {
        logListener?.let { listener ->
            handler.post { listener(msg) }
        }
        Log.d(TAG, msg)
    }

    private fun logDebug(msg: String) {
        if (mode == "debug") {
            logToUI("D/$TAG: $msg")
        }
    }

    private fun logInfo(msg: String) {
        logToUI("I/$TAG: $msg")
    }

    private fun logError(msg: String, e: Throwable? = null) {
        logToUI("E/$TAG: $msg" + (e?.let { " - ${it.message}" } ?: ""))
        if (e != null) Log.e(TAG, msg, e)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        logInfo("Service created")
        loadConfig()
    }

    private fun loadConfig() {
        try {
            val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
            config = JSONObject(jsonString)
            mode = config.getString("mode")
            logInfo("Config loaded: mode=$mode")
        } catch (e: Exception) {
            logError("Failed to load config", e)
            config = JSONObject()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logInfo("Service started with startId=$startId")

        val notification = createNotification()
        startForeground(1, notification)

        Thread {
            try {
                extractAssets()
                startServer()
            } catch (e: Exception) {
                logError("Error in service thread", e)
            }
        }.start()

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("qjsrht Server")
            .setContentText("QuickJS HTTP server is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Server Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun extractAssets() {
        logInfo("Starting asset extraction...")
        val appDir = filesDir
        val arch = getArchitecture()
        val networkType = try { config.getJSONObject("network").getString("type") } catch (e: Exception) { "local" }

        logInfo("Architecture: $arch, Network type: $networkType")

        extractAsset("$arch/qjs", File(appDir, "qjs"))
        extractAsset("$arch/qjsnet.so", File(appDir, "qjsnet.so"))
        extractAsset("express.js", File(appDir, "express.js"))
        extractAsset("server.js", File(appDir, "server.js"))

        if (networkType == "onion") {
            val torFile = File(appDir, "tor")
            if (!torFile.exists()) {
                extractAsset("$arch/tor", torFile)
            } else {
                logDebug("Tor binary already exists")
            }
            torFile.setExecutable(true)
            extractAsset("tor/torrc", File(appDir, "torrc"))
        }

        File(appDir, "qjs").setExecutable(true)

        logInfo("Files in app dir:")
        appDir.listFiles()?.forEach {
            logInfo("  ${it.name} (${it.length()} bytes, executable=${it.canExecute()})")
        }
    }

    private fun extractAsset(assetPath: String, dest: File) {
        logDebug("Extracting $assetPath")
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            logDebug("Extracted $assetPath")
        } catch (e: Exception) {
            logError("Failed to extract $assetPath", e)
        }
    }

    private fun getArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
            abi.contains("armeabi") -> "arm32"
            else -> "arm32"
        }
    }

    private fun startServer() {
        logInfo("Starting server...")
        val appDir = filesDir
        val networkObj = try { config.getJSONObject("network") } catch (e: Exception) { JSONObject() }
        val networkType = networkObj.optString("type", "local")
        val networkPort = networkObj.optInt("port", 8080)

        // Dirección donde escuchará el servidor (siempre local)
        val bindAddress = if (networkType == "onion") {
            "0.0.0.0"
        } else {
            val addr = networkObj.optString("address", "")
            if (addr.isBlank()) "0.0.0.0" else addr
        }

        // Si es onion, iniciar Tor y obtener dirección onion para información
        var onionAddress = ""
        if (networkType == "onion") {
            startTor(appDir)
            Thread.sleep(10000)
            val hostnameFile = File(appDir, "hidden_service/hostname")
            if (hostnameFile.exists()) {
                onionAddress = hostnameFile.readText().trim()
                logInfo("Onion address: $onionAddress")
            } else {
                logError("Hostname file not found")
            }
        }

        logInfo("Binding to $bindAddress:$networkPort")

        // Preparar server_run.js con la dirección de bind correcta
        val serverJsFile = File(appDir, "server.js")
        if (!serverJsFile.exists()) {
            logError("server.js not found")
            return
        }
        val serverJsContent = serverJsFile.readText()
        val modifiedContent = "const ADDRESS = '$bindAddress';\nconst PORT = $networkPort;\n" + serverJsContent
        File(appDir, "server_run.js").writeText(modifiedContent)

        val qjsPath = File(appDir, "qjs").absolutePath
        val serverPath = File(appDir, "server_run.js").absolutePath

        logInfo("Executing: $qjsPath -m $serverPath")
        val processBuilder = ProcessBuilder(qjsPath, "-m", serverPath)
        processBuilder.directory(appDir)
        processBuilder.environment()["LD_LIBRARY_PATH"] = appDir.absolutePath
        processBuilder.redirectErrorStream(true)

        try {
            qjsProcess = processBuilder.start()
            logInfo("QuickJS started")

            Thread {
                val reader = BufferedReader(InputStreamReader(qjsProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logToUI("QJS: $line")
                }
            }.start()
        } catch (e: Exception) {
            logError("Failed to start QuickJS", e)
        }
    }

    private fun startTor(appDir: File) {
        logInfo("Starting Tor...")
        val torPath = File(appDir, "tor").absolutePath
        val torrcPath = File(appDir, "torrc").absolutePath
        val dataDir = File(appDir, "tor_data")
        dataDir.mkdirs()

        val processBuilder = ProcessBuilder(torPath, "-f", torrcPath, "--DataDirectory", dataDir.absolutePath)
        processBuilder.directory(appDir)
        processBuilder.redirectErrorStream(true)

        try {
            torProcess = processBuilder.start()
            logInfo("Tor started")
            Thread {
                val reader = BufferedReader(InputStreamReader(torProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logToUI("TOR: $line")
                }
            }.start()
        } catch (e: Exception) {
            logError("Failed to start Tor", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logInfo("Service destroyed")
        qjsProcess?.destroy()
        torProcess?.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
