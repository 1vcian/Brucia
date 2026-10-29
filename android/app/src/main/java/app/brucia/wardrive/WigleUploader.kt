package app.brucia.wardrive

import android.util.Base64
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Status of a WiGLE upload, surfaced to the UI as a StateFlow. */
sealed class WigleUploadStatus {
    object Idle : WigleUploadStatus()
    object Uploading : WigleUploadStatus()
    data class Success(val message: String) : WigleUploadStatus()
    data class Error(val message: String) : WigleUploadStatus()
}

/**
 * Uploads a WiGLE-format CSV straight to wigle.net, the way Marauder does: HTTP Basic auth
 * (API Name + API Token) and a multipart/form-data POST. Uses HttpURLConnection to match
 * the rest of the app (see WordlistStore); never logs the token.
 */
object WigleUploader {

    private const val ENDPOINT = "https://api.wigle.net/api/v2/file/upload"

    /** Outcome of an upload. [transid] is WiGLE's transaction id when the server reports it. */
    sealed class Result {
        data class Success(val transid: String?, val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * POST [csv] as the WiGLE `file` part. Blocking: call on Dispatchers.IO.
     * [apiName] is the WiGLE "API Name" (encoded token user), [apiToken] the "API Token".
     */
    fun upload(apiName: String, apiToken: String, csv: ByteArray, filename: String): Result {
        val boundary = "----BruciaWigle" + System.currentTimeMillis()
        val auth = Base64.encodeToString(
            "$apiName:$apiToken".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Brucia")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Basic $auth")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                val nl = "\r\n"
                out.writeBytes("--$boundary$nl")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"$nl"
                )
                out.writeBytes("Content-Type: text/csv$nl$nl")
                out.write(csv)
                out.writeBytes(nl)
                out.writeBytes("--$boundary--$nl")
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return parseResponse(code, body)
        } catch (e: Exception) {
            return Result.Failure("Network error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /** Parse WiGLE's JSON response. Visible for testing; robust to the known shapes. */
    fun parseResponse(httpCode: Int, body: String): Result {
        val json = try { JSONObject(body) } catch (e: Exception) { null }
            ?: return if (httpCode in 200..299) Result.Success(null, "Uploaded")
            else Result.Failure("HTTP $httpCode")

        val success = json.optBoolean("success", httpCode in 200..299)
        if (!success) {
            val msg = json.optString("message").ifBlank {
                json.optString("error").ifBlank { "Upload failed (HTTP $httpCode)" }
            }
            return Result.Failure(msg)
        }

        val transid = extractTransid(json)
        val msg = json.optString("message").ifBlank {
            if (transid != null) "Uploaded (transid $transid)" else "Uploaded"
        }
        return Result.Success(transid, msg)
    }

    private fun extractTransid(json: JSONObject): String? {
        json.optString("transid").ifBlank { null }?.let { return it }
        json.optJSONObject("results")?.optString("transid")?.ifBlank { null }?.let { return it }
        json.optJSONArray("results")?.let { arr ->
            if (arr.length() > 0) {
                arr.optJSONObject(0)?.optString("transid")?.ifBlank { null }?.let { return it }
            }
        }
        json.optJSONArray("transids")?.let { arr ->
            if (arr.length() > 0) arr.optString(0).ifBlank { null }?.let { return it }
        }
        return null
    }
}
