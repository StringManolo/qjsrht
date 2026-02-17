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
import java.io.BufferedReader
import java.io.InputStreamReader

class ServerService : Service() {
    private val TAG = "ServerService"
    private var qjsProcess: Process? = null
    private var torProcess: Process? = null
    private val CHANNEL_ID = "ServerServiceChannel"
    private lateinit var config: JSONObject
    private var isDebug = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        loadConfig()
    }

    private fun loadConfig() {
        try {
            val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
            config = JSONObject(jsonString)
            isDebug = config.getString("mode") == "debug"
            Log.d(TAG, "Config loaded: mode=$isDebug, config=$config")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            // Fallback a valores por defecto para no romper
            config = JSONObject()
            isDebug = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent: $intent")

        val notification = createNotification()
        startForeground(1, notification)

        Thread {
            try {
                logDebug("Starting extraction and server...")
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

    private fun logDebug(msg: String) {
        if (isDebug) Log.d(TAG, msg)
    }

    private fun extractAssets() {
        logDebug("Extracting assets...")
        val appDir = filesDir
        val arch = getArchitecture()
        val networkType = try { config.getJSONObject("network").getString("type") } catch (e: Exception) { "local" }

        logDebug("Architecture: $arch, networkType: $networkType")

        // Extraer binarios
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
            logDebug("Tor permissions set")

            // torrc
            extractAsset("tor/torrc", File(appDir, "torrc"))

            // Hidden service files
            try {
                val hsDir = File(appDir, "hidden_service")
                hsDir.mkdirs()
                extractAsset("tor/hidden_service/hostname", File(hsDir, "hostname"))
                extractAsset("tor/hidden_service/hs_ed25519_public_key", File(hsDir, "hs_ed25519_public_key"))
                extractAsset("tor/hidden_service/hs_ed25519_secret_key", File(hsDir, "hs_ed25519_secret_key"))
                logDebug("Hidden service files extracted")
            } catch (e: Exception) {
                logDebug("No hidden service files found in assets, will be created by Tor: ${e.message}")
            }
        }

        // Permisos qjs
        File(appDir, "qjs").setExecutable(true)
        logDebug("qjs permissions set")

        logDebug("Assets extracted successfully")
    }

    private fun extractAsset(assetPath: String, dest: File) {
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            logDebug("Extracted: $assetPath -> ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $assetPath", e)
        }
    }

    private fun getArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        logDebug("Device ABI: $abi")
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
            abi.contains("armeabi") -> "arm32"
            else -> "arm32"
        }
    }

    private fun startServer() {
        logDebug("Starting server...")
        val appDir = filesDir
        val networkObj = try { config.getJSONObject("network") } catch (e: Exception) { JSONObject() }
        val networkType = try { networkObj.getString("type") } catch (e: Exception) { "local" }
        val networkAddress = try { networkObj.getString("address") } catch (e: Exception) { "" }
        val networkPort = try { networkObj.getInt("port") } catch (e: Exception) { 8080 }
        val mode = try { config.getString("mode") } catch (e: Exception) { "debug" }

        logDebug("networkType=$networkType, address=$networkAddress, port=$networkPort")

        if (networkType == "onion") {
            startTor(appDir, mode)
            Thread.sleep(10000) // Esperar a que Tor genere el servicio
        }

        val address = when (networkType) {
            "onion" -> {
                val hostnameFile = File(appDir, "hidden_service/hostname")
                if (hostnameFile.exists()) {
                    hostnameFile.readText().trim().also {
                        logDebug("Onion address: $it")
                    }
                } else {
                    logDebug("hostname file not found, using unknown.onion")
                    "unknown.onion"
                }
            }
            else -> networkAddress
        }
        val port = networkPort

        logDebug("Final server address: $address:$port")

        // Modificar server.js con la dirección y puerto
        val serverJsFile = File(appDir, "server.js")
        if (!serverJsFile.exists()) {
            Log.e(TAG, "server.js not found!")
            return
        }
        val serverJsContent = serverJsFile.readText()
        val modifiedContent = "const ADDRESS = '$address';\nconst PORT = $port;\n" + serverJsContent
        File(appDir, "server_run.js").writeText(modifiedContent)
        logDebug("server_run.js created")

        // Ejecutar QuickJS
        val qjsPath = File(appDir, "qjs").absolutePath
        val serverPath = File(appDir, "server_run.js").absolutePath

        val processBuilder = ProcessBuilder(qjsPath, "-m", serverPath)
        processBuilder.directory(appDir)
        val env = processBuilder.environment()
        env["LD_LIBRARY_PATH"] = appDir.absolutePath

        if (mode == "debug") {
            processBuilder.redirectErrorStream(true)
        }

        logDebug("Starting QJS process: ${processBuilder.command()}")

        qjsProcess = processBuilder.start()

        if (mode == "debug") {
            // Leer salida de QJS en tiempo real
            Thread {
                val reader = BufferedReader(InputStreamReader(qjsProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "QJS: $line")
                }
            }.start()
        }

        logDebug("QuickJS started with PID: ${qjsProcess?.pid()}")
    }

    private fun startTor(appDir: File, mode: String) {
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

        logDebug("Tor command: ${processBuilder.command()}")

        torProcess = processBuilder.start()

        if (mode == "debug") {
            Thread {
                val reader = BufferedReader(InputStreamReader(torProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "TOR: $line")
                }
            }.start()
        }

        logDebug("Tor started with PID: ${torProcess?.pid()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        logDebug("Service destroyed")
        qjsProcess?.destroy()
        torProcess?.destroy()
        qjsProcess = null
        torProcess = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
