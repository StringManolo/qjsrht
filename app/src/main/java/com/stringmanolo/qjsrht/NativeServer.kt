package com.stringmanolo.qjsrht

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.*
import android.util.Log
import com.google.gson.Gson
import java.io.*

class NativeServer(private val context: Context) {
    private val TAG = "NativeServer"
    private val gson = Gson()
    private var running = true
    private lateinit var reqFifo: File
    private lateinit var respFifo: File
    private var reqReader: BufferedReader? = null
    private var respWriter: PrintWriter? = null

    fun start() {
        val filesDir = context.filesDir.absolutePath
        reqFifo = File(filesDir, "native_req")
        respFifo = File(filesDir, "native_resp")

        // Remove old FIFOs if they exist
        reqFifo.delete()
        respFifo.delete()

        // Create FIFOs using mkfifo command
        try {
            val processReq = ProcessBuilder("mkfifo", reqFifo.absolutePath).start()
            processReq.waitFor()
            val processResp = ProcessBuilder("mkfifo", respFifo.absolutePath).start()
            processResp.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create FIFOs", e)
            return
        }

        Thread {
            try {
                Log.i(TAG, "Waiting for connection on FIFOs...")
                // Open in correct order to avoid deadlock:
                // Open for reading first, then writing? Actually we need to open both ends.
                // To avoid deadlock, we can open one end in a separate thread, but here we'll open sequentially.
                // Standard approach: open read end of resp FIFO first? Actually the order is:
                // - Writer opens write end of req FIFO (blocks until reader opens read end)
                // - Reader opens read end of resp FIFO (blocks until writer opens write end)
                // To avoid deadlock, we need to open them in a way that doesn't block each other.
                // We can open both in non-blocking mode? But FIFOs on Android might not support non-blocking easily.
                // Simpler: open in separate threads. But here we are already in a thread, so we can do:
                // 1. Open reqFifo for reading (this will block until someone opens it for writing)
                // 2. Then open respFifo for writing (this will block until someone opens it for reading)
                // The other side (server.js) will open respFifo for reading and reqFifo for writing.
                // So we need to open in reverse order: first open respFifo for writing? Let's think:
                // server.js does: nativeReqFd = std.open(reqPath, 'w'); (opens write end of req)
                // and nativeRespFd = std.open(respPath, 'r'); (opens read end of resp)
                // So here we need: open reqFifo for reading, and respFifo for writing.
                // Order: if we open reqFifo for reading first, it will block until server.js opens it for writing.
                // Then we open respFifo for writing, which will block until server.js opens it for reading.
                // That works sequentially because server.js will open both in its own order.
                // But server.js opens reqFifo first, then respFifo. So if we open reqFifo for reading first, it will unblock when server.js opens it for writing. Then we open respFifo for writing, which will unblock when server.js opens it for reading. That's fine.
                val reqInput = FileInputStream(reqFifo).bufferedReader()
                val respOutput = FileOutputStream(respFifo).printWriter()
                reqReader = reqInput
                respWriter = respOutput
                Log.i(TAG, "Native FIFO connected")

                while (running) {
                    val line = reqInput.readLine() ?: break
                    val request = gson.fromJson(line, NativeRequest::class.java)
                    val response = processRequest(request)
                    respOutput.println(gson.toJson(response))
                    respOutput.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "FIFO error", e)
            } finally {
                reqReader?.close()
                respWriter?.close()
                reqFifo.delete()
                respFifo.delete()
            }
        }.start()
    }

    fun stop() {
        running = false
        reqReader?.close()
        respWriter?.close()
        reqFifo.delete()
        respFifo.delete()
    }

    private fun processRequest(request: NativeRequest): Map<String, Any?> {
        return try {
            when (request.action) {
                "contacts" -> getContacts()
                "clipboard" -> getClipboard()
                "location" -> getLocation()
                "sms" -> getSms()
                "callLog" -> getCallLog()
                "apps" -> getInstalledApps()
                "settings" -> changeSetting(request.params)
                else -> mapOf("error" to "Unknown action")
            }
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }

    private fun getContacts(): Map<String, Any> {
        val cr = context.contentResolver
        val cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null)
        val contacts = mutableListOf<Map<String, String>>()
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                contacts.add(mapOf(
                    "name" to (it.getString(nameIndex) ?: ""),
                    "number" to (it.getString(numberIndex) ?: "")
                ))
            }
        }
        return mapOf("contacts" to contacts)
    }

    private fun getClipboard(): Map<String, Any> {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
        return mapOf("clipboard" to text)
    }

    private fun getLocation(): Map<String, Any> {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var location: Location? = null
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }
        if (location == null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }
        return if (location != null) {
            mapOf(
                "lat" to location.latitude,
                "lon" to location.longitude,
                "accuracy" to location.accuracy
            )
        } else {
            mapOf("error" to "No location available")
        }
    }

    private fun getSms(): Map<String, Any> {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null, null, null,
            Telephony.Sms.DEFAULT_SORT_ORDER
        )
        val messages = mutableListOf<Map<String, String>>()
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                messages.add(mapOf(
                    "address" to (it.getString(addressIdx) ?: ""),
                    "body" to (it.getString(bodyIdx) ?: ""),
                    "date" to (it.getString(dateIdx) ?: "")
                ))
            }
        }
        return mapOf("sms" to messages)
    }

    private fun getCallLog(): Map<String, Any> {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null,
            CallLog.Calls.DATE + " DESC"
        )
        val calls = mutableListOf<Map<String, String>>()
        cursor?.use {
            val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
            while (it.moveToNext()) {
                calls.add(mapOf(
                    "number" to (it.getString(numberIdx) ?: ""),
                    "type" to (it.getString(typeIdx) ?: ""),
                    "date" to (it.getString(dateIdx) ?: ""),
                    "duration" to (it.getString(durationIdx) ?: "")
                ))
            }
        }
        return mapOf("calls" to calls)
    }

    private fun getInstalledApps(): Map<String, Any> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val apps = packages.map {
            mapOf(
                "packageName" to it.packageName,
                "name" to pm.getApplicationLabel(it).toString()
            )
        }
        return mapOf("apps" to apps)
    }

    private fun changeSetting(params: Map<String, Any?>?): Map<String, Any> {
        if (params?.get("key") == "wifi_on") {
            val value = params["value"] as? Boolean ?: return mapOf("error" to "Invalid value")
            val wifiOn = if (value) 1 else 0
            android.provider.Settings.Global.putInt(
                context.contentResolver,
                android.provider.Settings.Global.WIFI_ON,
                wifiOn
            )
            return mapOf("success" to true)
        }
        return mapOf("error" to "Unsupported setting")
    }

    data class NativeRequest(val action: String, val params: Map<String, Any?>?)
}
