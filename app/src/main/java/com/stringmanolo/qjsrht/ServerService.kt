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
import java.io.File
import java.io.FileOutputStream

class ServerService : Service() {
    private val TAG = "ServerService"
    private var qjsProcess: Process? = null
    private var torProcess: Process? = null
    private val CHANNEL_ID = "ServerServiceChannel"
    private lateinit var config: JSONObject

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        loadConfig()
    }

    private fun loadConfig() {
        val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
        config = JSONObject(jsonString)
        Log.d(TAG, "Config loaded: $config")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

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
        Log.d(TAG, "Extracting assets...")
        val appDir = filesDir
        val arch = getArchitecture()
        val networkType = config.getJSONObject("network").getString("type")

        Log.d(TAG, "Architecture: $arch")

        // Extract binaries
        extractAsset("$arch/qjs", File(appDir, "qjs"))
        extractAsset("$arch/qjsnet.so", File(appDir, "qjsnet.so"))

        // Extract JavaScript files
        extractAsset("express.js", File(appDir, "express.js"))
        extractAsset("server.js", File(appDir, "server.js"))

        // If onion mode, extract Tor
        if (networkType == "onion") {
            extractAsset("$arch/tor", File(appDir, "tor"))
            extractAsset("tor/torrc", File(appDir, "torrc"))

            try {
                val hsDir = File(appDir, "hidden_service")
                hsDir.mkdirs()
                extractAsset("tor/hidden_service/hostname", File(hsDir, "hostname"))
                extractAsset("tor/hidden_service/hs_ed25519_public_key", File(hsDir, "hs_ed25519_public_key"))
                extractAsset("tor/hidden_service/hs_ed25519_secret_key", File(hsDir, "hs_ed25519_secret_key"))
            } catch (e: Exception) {
                Log.w(TAG, "No hidden service files found in assets, will be created by Tor")
            }
        }

        // Set execute permissions
        File(appDir, "qjs").setExecutable(true)
        if (networkType == "onion") {
            File(appDir, "tor").setExecutable(true)
        }

        Log.d(TAG, "Assets extracted successfully")
    }

    private fun extractAsset(assetPath: String, dest: File) {
        assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Extracted: $assetPath -> ${dest.absolutePath}")
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
        Log.d(TAG, "Starting server...")
        val appDir = filesDir
        val networkObj = config.getJSONObject("network")
        val networkType = networkObj.getString("type")
        val networkAddress = networkObj.getString("address")
        val networkPort = networkObj.getInt("port")
        val mode = config.getString("mode")

        if (networkType == "onion") {
            startTor(appDir, mode)
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

        Log.d(TAG, "Server will bind to: $address:$port")

        val serverJsContent = File(appDir, "server.js").readText()
        val modifiedContent = "const ADDRESS = '$address';\nconst PORT = $port;\n" + serverJsContent
        File(appDir, "server_run.js").writeText(modifiedContent)

        val qjsPath = File(appDir, "qjs").absolutePath
        val serverPath = File(appDir, "server_run.js").absolutePath

        val processBuilder = ProcessBuilder(qjsPath, "-m", serverPath)
        processBuilder.directory(appDir)
        val env = processBuilder.environment()
        env["LD_LIBRARY_PATH"] = appDir.absolutePath

        if (mode == "debug") {
            processBuilder.redirectErrorStream(true)
        }

        qjsProcess = processBuilder.start()

        if (mode == "debug") {
            Thread {
                qjsProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lineSequence().forEach { line ->
                        Log.d(TAG, "QJS: $line")
                    }
                }
            }.start()
        }

        Log.d(TAG, "QuickJS started")
    }

    private fun startTor(appDir: File, mode: String) {
        Log.d(TAG, "Starting Tor...")

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

        if (mode == "debug") {
            Thread {
                torProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lineSequence().forEach { line ->
                        Log.d(TAG, "TOR: $line")
                    }
                }
            }.start()
        }

        Log.d(TAG, "Tor started")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        qjsProcess?.destroy()
        torProcess?.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
