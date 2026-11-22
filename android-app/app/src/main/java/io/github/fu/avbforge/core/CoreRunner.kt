package io.github.fu.avbforge.core

import android.content.Context
import io.github.fu.avbforge.data.CoreProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class CoreRunner(private val context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun coreBinaryPath(): File =
        File(context.applicationInfo.nativeLibraryDir, "libavbforge_core.so")

    suspend fun run(
        args: List<String>,
        requireRoot: Boolean = true,
        timeoutSeconds: Long = 600,
        onLog: (String, String) -> Unit = { _, _ -> },
        onProgress: (CoreProgress) -> Unit = {},
    ): Result<JsonElement> = withContext(Dispatchers.IO) {
        val bin = coreBinaryPath()
        if (!bin.exists()) {
            return@withContext Result.failure(
                IllegalStateException("Native core binary not found: ${bin.absolutePath}")
            )
        }

        val cmd = if (requireRoot) {
            val escaped = (listOf(bin.absolutePath) + args).joinToString(" ") { shellQuote(it) }
            listOf("su", "-c", escaped)
        } else {
            listOf(bin.absolutePath) + args
        }

        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        var resultPayload: JsonElement? = null
        val errLines = mutableListOf<String>()

        val stderrThread = Thread {
            BufferedReader(InputStreamReader(proc.errorStream)).use { br ->
                br.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val parsedErr = runCatching {
                            val obj = json.parseToJsonElement(trimmed).jsonObject
                            if (obj["event"]?.jsonPrimitive?.content == "error") {
                                obj["message"]?.jsonPrimitive?.content
                            } else null
                        }.getOrNull()
                        errLines += (parsedErr ?: trimmed)
                        onLog("error", parsedErr ?: trimmed)
                    }
                }
            }
        }.apply { start() }

        BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
            br.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val parsed = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
                if (parsed == null) {
                    onLog("info", trimmed)
                    return@forEach
                }
                when (parsed["event"]?.jsonPrimitive?.content) {
                    "log" -> {
                        val lvl = parsed["level"]?.jsonPrimitive?.content ?: "info"
                        val msg = parsed["message"]?.jsonPrimitive?.content ?: ""
                        onLog(lvl, msg)
                    }
                    "progress" -> {
                        val stage = parsed["stage"]?.jsonPrimitive?.content ?: ""
                        val cur = parsed["current"]?.jsonPrimitive?.longOrNull ?: 0L
                        val tot = parsed["total"]?.jsonPrimitive?.longOrNull ?: 0L
                        onProgress(CoreProgress(stage, cur, tot))
                    }
                    "result" -> {
                        resultPayload = parsed["data"]
                    }
                    "error" -> {
                        val msg = parsed["message"]?.jsonPrimitive?.content ?: "Unknown error"
                        errLines += msg
                        onLog("error", msg)
                    }
                }
            }
        }

        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return@withContext Result.failure(
                IllegalStateException("命令执行超时（>${timeoutSeconds}s），已强制中止")
            )
        }
        stderrThread.join(2000)

        val exitCode = proc.exitValue()
        if (exitCode != 0) {
            val msg = errLines.lastOrNull() ?: "avbforge-core exited with code $exitCode"
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val payload = resultPayload
            ?: return@withContext Result.failure(IllegalStateException("Core exited 0 without result payload"))
        Result.success(payload)
    }

    private fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\"'\"'") + "'"
}
