package com.stringmanolo.qjsrht

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class ServerService : Service() {
    private val TAG = "ServerService"
    private var qjsProcess: Process? = null
    private var torProcess: Process? = null
    private val CHANNEL_ID = "ServerServiceChannel"
    private lateinit var config: JSONObject
    private var mode: String = "debug"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        loadConfig()
    }

    private fun loadConfig() {
        val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
        config = JSONObject(jsonString)
        mode = config.getString("mode")
        logDebug("Config loaded: $config")
    }

    private fun logDebug(msg: String) {
        if (mode == "debug") {
            Log.d(TAG, msg)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logDebug("Service started")

        val notification = createNotification()
        startForeground(1, notification)

        Thread {
            try {
                extractAssets()
                startServer()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server: ${e.message}", e)
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
        logDebug("Extracting assets...")
        val appDir = filesDir
        val arch = getArchitecture()
        val networkType = config.getJSONObject("network").getString("type")

        logDebug("Architecture: $arch")

        // Extraer binarios (siempre sobrescribir qjs y qjsnet.so)
        extractAsset("$arch/qjs", File(appDir, "qjs"))
        extractAsset("$arch/qjsnet.so", File(appDir, "qjsnet.so"))

        // Extraer JavaScript
        extractAsset("express.js", File(appDir, "express.js"))
        extractAsset("server.js", File(appDir, "server.js"))

        // Si es onion, manejar Tor
        if (networkType == "onion") {
            val torFile = File(appDir, "tor")
            if (!torFile.exists()) {
                extractAsset("$arch/tor", torFile)
            } else {
                logDebug("Tor binary already exists, skipping extraction")
            }
            torFile.setExecutable(true)

            extractAsset("tor/torrc", File(appDir, "torrc"))

            try {
                val hsDir = File(appDir, "hidden_service")
                hsDir.mkdirs()
                extractAsset("tor/hidden_service/hostname", File(hsDir, "hostname"))
                extractAsset("tor/hidden_service/hs_ed25519_public_key", File(hsDir, "hs_ed25519_public_key"))
                extractAsset("tor/hidden_service/hs_ed25519_secret_key", File(hsDir, "hs_ed25519_secret_key"))
            } catch (e: Exception) {
                logDebug("No hidden service files found in assets, will be created by Tor")
            }
        }

        File(appDir, "qjs").setExecutable(true)

        logDebug("Assets extracted successfully")
    }

    private fun extractAsset(assetPath: String, dest: File) {
        logDebug("Extracting $assetPath -> ${dest.absolutePath}")
        assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
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
        logDebug("Starting server...")
        val appDir = filesDir
        val networkObj = config.getJSONObject("network")
        val networkType = networkObj.getString("type")
        val networkAddress = networkObj.getString("address")
        val networkPort = networkObj.getInt("port")

        if (networkType == "onion") {
            startTor(appDir)
            Thread.sleep(10000)
        }

        val address = when (networkType) {
            "onion" -> {
                val hostnameFile = File(appDir, "hidden_service/hostname")
                if (hostnameFile.exists()) {
                    hostnameFile.readText().trim()
                } else {
                    "unknown.onion"
                }
            }
            else -> networkAddress
        }
        val port = networkPort

        logDebug("Server will bind to: $address:$port")

        val serverJsContent = File(appDir, "server.js").readText()
        val modifiedContent = "const ADDRESS = '$address';\nconst PORT = $port;\n" + serverJsContent
        File(appDir, "server_run.js").writeText(modifiedContent)

        val qjsPath = File(appDir, "qjs").absolutePath
        val serverPath = File(appDir, "server_run.js").absolutePath
        val qjsnetPath = File(appDir, "qjsnet.so").absolutePath

        val processBuilder = ProcessBuilder(qjsPath, "-m", serverPath)
        processBuilder.directory(appDir)
        val env = processBuilder.environment()
        env["LD_LIBRARY_PATH"] = appDir.absolutePath

        if (mode == "debug") {
            processBuilder.redirectErrorStream(true)
        }

        qjsProcess = processBuilder.start()
        logDebug("QuickJS started (PID: ${qjsProcess?.pid()})")

        if (mode == "debug") {
            Thread {
                val reader = BufferedReader(InputStreamReader(qjsProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "QJS: $line")
                }
            }.start()
        }
    }

    private fun startTor(appDir: File) {
        logDebug("Starting Tor...")

        val torPath = File(appDir, "tor").absolutePath
        val torrcPath = File(appDir, "torrc").absolutePath
        val dataDir = File(appDir, "tor_data")
        dataDir.mkdirs()

        val processBuilder = ProcessBuilder(
            torPath,
            "-f", torrcPath,
            "--DataDirectory", dataDir.absolutePath
        )
        processBuilder.directory(appDir)

        if (mode == "debug") {
            processBuilder.redirectErrorStream(true)
        }

        torProcess = processBuilder.start()
        logDebug("Tor started (PID: ${torProcess?.pid()})")

        if (mode == "debug") {
            Thread {
                val reader = BufferedReader(InputStreamReader(torProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "TOR: $line")
                }
            }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logDebug("Service destroyed")
        qjsProcess?.destroy()
        torProcess?.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
