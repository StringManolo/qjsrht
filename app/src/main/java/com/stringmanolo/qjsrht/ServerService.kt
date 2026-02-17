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
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class ServerService : Service() {
    private val TAG = "ServerService"
    private var qjsProcess: Process? = null
    private var torProcess: Process? = null
    private val CHANNEL_ID = "ServerServiceChannel"
    private lateinit var config: JSONObject
    private var mode: String = "debug"
    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        logFile = File(filesDir, "logs.txt")
        // Limpiar logs al iniciar el servicio (opcional)
        // logFile.writeText("")
        logDebug("Service created")
        loadConfig()
    }

    private fun logToFile(level: String, tag: String, msg: String) {
        try {
            val time = dateFormat.format(Date())
            val line = "$time $level/$tag: $msg\n"
            logFile.appendText(line)
        } catch (e: Exception) {
            // Si falla, al menos usar Log
            Log.e(TAG, "Error writing to log file: ${e.message}")
        }
    }

    private fun logDebug(msg: String) {
        val fullMsg = "$msg"
        Log.d(TAG, fullMsg)
        if (mode == "debug") {
            logToFile("D", TAG, fullMsg)
        }
    }

    private fun logInfo(msg: String) {
        val fullMsg = "$msg"
        Log.i(TAG, fullMsg)
        logToFile("I", TAG, fullMsg)
    }

    private fun logError(msg: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.e(TAG, fullMsg, e)
        logToFile("E", TAG, fullMsg)
        e?.printStackTrace()
    }

    private fun loadConfig() {
        try {
            val jsonString = assets.open("config.json").bufferedReader().use { it.readText() }
            config = JSONObject(jsonString)
            mode = config.getString("mode")
            logInfo("Config loaded: mode=$mode, network=${config.getJSONObject("network")}")
        } catch (e: Exception) {
            logError("Failed to load config", e)
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

        logInfo("Architecture detected: $arch")
        logInfo("Network type: $networkType")

        // List assets antes de extraer (para debug)
        try {
            val assetList = assets.list("")?.joinToString() ?: "empty"
            logDebug("Assets in root: $assetList")
            val archAssets = assets.list(arch)?.joinToString() ?: "empty"
            logDebug("Assets in $arch: $archAssets")
        } catch (e: Exception) {
            logError("Failed to list assets", e)
        }

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

        // Dar permisos de ejecución a qjs
        File(appDir, "qjs").setExecutable(true)

        // Verificar que los archivos están
        logDebug("Files in app dir after extraction:")
        File(appDir).listFiles()?.forEach {
            logDebug("  ${it.name} (${if (it.canExecute()) "executable" else "not executable"})")
        }

        logInfo("Asset extraction completed")
    }

    private fun extractAsset(assetPath: String, dest: File) {
        logDebug("Extracting $assetPath -> ${dest.absolutePath}")
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            logDebug("Extraction successful")
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
        val networkAddress = networkObj.optString("address", "")
        val networkPort = networkObj.optInt("port", 8080)

        logInfo("Network config: type=$networkType, address=$networkAddress, port=$networkPort")

        if (networkType == "onion") {
            startTor(appDir)
            Thread.sleep(10000)
        }

        val address = when (networkType) {
            "onion" -> {
                val hostnameFile = File(appDir, "hidden_service/hostname")
                if (hostnameFile.exists()) {
                    hostnameFile.readText().trim().also {
                        logInfo("Onion address: $it")
                    }
                } else {
                    logError("Hostname file not found")
                    "unknown.onion"
                }
            }
            else -> networkAddress
        }
        val port = networkPort

        logInfo("Server will bind to: $address:$port")

        // Crear server_run.js con la dirección y puerto
        val serverJsFile = File(appDir, "server.js")
        if (!serverJsFile.exists()) {
            logError("server.js not found")
            return
        }
        val serverJsContent = serverJsFile.readText()
        val modifiedContent = "const ADDRESS = '$address';\nconst PORT = $port;\n" + serverJsContent
        File(appDir, "server_run.js").writeText(modifiedContent)
        logDebug("server_run.js created")

        val qjsPath = File(appDir, "qjs").absolutePath
        val serverPath = File(appDir, "server_run.js").absolutePath

        logInfo("Executing: $qjsPath -m $serverPath")
        val processBuilder = ProcessBuilder(qjsPath, "-m", serverPath)
        processBuilder.directory(appDir)
        val env = processBuilder.environment()
        env["LD_LIBRARY_PATH"] = appDir.absolutePath

        if (mode == "debug") {
            processBuilder.redirectErrorStream(true)
        }

        try {
            qjsProcess = processBuilder.start()
            logInfo("QuickJS started")

            // Capturar salida del proceso y escribir al archivo de logs
            Thread {
                val reader = BufferedReader(InputStreamReader(qjsProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logToFile("I", "QJS", line!!)
                }
            }.start()

            // También capturar flujo de error por si acaso (aunque redirectErrorStream=true los combina)
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

        logInfo("Tor command: $torPath -f $torrcPath --DataDirectory ${dataDir.absolutePath}")
        val processBuilder = ProcessBuilder(
            torPath,
            "-f", torrcPath,
            "--DataDirectory", dataDir.absolutePath
        )
        processBuilder.directory(appDir)

        if (mode == "debug") {
            processBuilder.redirectErrorStream(true)
        }

        try {
            torProcess = processBuilder.start()
            logInfo("Tor started")

            Thread {
                val reader = BufferedReader(InputStreamReader(torProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logToFile("I", "TOR", line!!)
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
