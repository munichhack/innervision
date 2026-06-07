package com.blindvision.planning.tools

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolve a natural-language destination request to a floor-plan bounding box.
 *
 * Compile:
 *   kotlinc tools/DestinationResolver.kt -include-runtime -d /tmp/destination-resolver.jar
 *
 * Run:
 *   set -a; source .env; set +a
 *   java -jar /tmp/destination-resolver.jar "I want to get to room 31"
 *   java -jar /tmp/destination-resolver.jar --current 900,850 "nearest staircase"
 *
 * --current uses the same pixel coordinate system as message.json.
 * Stdout is always either [minX,minY,maxX,maxY] or -1.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(USAGE)
        kotlin.system.exitProcess(0)
    }

    val exitCode = try {
        val options = CliOptions.parse(args)
        val apiKey = env("GEMINI_API_KEY") ?: env("GOOGLE_API_KEY")
            ?: throw IllegalStateException("GEMINI_API_KEY or GOOGLE_API_KEY must be set.")

        val mapJson = readMapJson(options.mapPath)
        val prompt = buildPrompt(mapJson, options.query, options.currentLocation)
        val rawAnswer = callGemini(apiKey, env("GEMINI_MODEL") ?: DEFAULT_MODEL, prompt)
        val normalized = normalizeModelAnswer(rawAnswer)

        if (normalized == null) {
            System.err.println("Gemini returned an unsupported response: ${rawAnswer.take(200)}")
            println("-1")
        } else {
            println(normalized)
        }
        0
    } catch (exc: UsageException) {
        System.err.println(exc.message)
        System.err.println(USAGE)
        2
    } catch (exc: Exception) {
        System.err.println("Destination resolver failed: ${exc.message}")
        1
    }
    kotlin.system.exitProcess(exitCode)
}

private const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
private const val DEFAULT_MAP_PATH = "json_map/message.json"

private val USAGE = """
Usage:
  java -jar /tmp/destination-resolver.jar [--map PATH] [--current X,Y] "destination request"

Examples:
  java -jar /tmp/destination-resolver.jar "I want to get to room 31"
  java -jar /tmp/destination-resolver.jar --current 900,850 "nearest staircase"
""".trimIndent()

private data class CliOptions(
    val mapPath: String,
    val currentLocation: Point?,
    val query: String,
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            var mapPath = DEFAULT_MAP_PATH
            var currentLocation: Point? = null
            val queryParts = ArrayList<String>()

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--map" -> {
                        i += 1
                        mapPath = args.getOrNull(i) ?: throw UsageException("--map requires a file path.")
                    }
                    "--current" -> {
                        i += 1
                        currentLocation = Point.parse(
                            args.getOrNull(i) ?: throw UsageException("--current requires X,Y.")
                        )
                    }
                    else -> queryParts.add(arg)
                }
                i += 1
            }

            val query = queryParts.joinToString(" ").trim()
            if (query.isBlank()) throw UsageException("A destination request is required.")

            return CliOptions(mapPath, currentLocation, query)
        }
    }
}

private data class Point(val x: Double, val y: Double) {
    companion object {
        fun parse(raw: String): Point {
            val parts = raw.split(",")
            if (parts.size != 2) throw UsageException("--current must look like X,Y.")
            val x = parts[0].trim().toDoubleOrNull()
            val y = parts[1].trim().toDoubleOrNull()
            if (x == null || y == null) throw UsageException("--current must contain numeric X,Y values.")
            return Point(x, y)
        }
    }
}

private class UsageException(message: String) : RuntimeException(message)

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

private fun readMapJson(path: String): String {
    val requested = File(path)
    if (requested.isFile) return requested.readText()

    val jsonDataFallback = File("json_data/message.json")
    if (path == DEFAULT_MAP_PATH && jsonDataFallback.isFile) return jsonDataFallback.readText()

    throw IllegalArgumentException("Map JSON not found at $path")
}

private fun buildPrompt(mapJson: String, userQuery: String, currentLocation: Point?): String {
    val locationText = currentLocation?.let { "x=${it.x}, y=${it.y}" } ?: "unknown"
    return """
You are a destination resolver for an indoor navigation app.

Map JSON:
$mapJson

User request:
$userQuery

Current location:
$locationText

Rules:
- Return exactly one line and nothing else.
- If the request identifies a room, match it to a room item id. Room numbers may be spoken without leading zeroes, so "room 31" matches id "0031".
- If the request identifies a notable location, match it to a notable_location item id.
- If the request asks for the nearest or closest staircase/stairs, choose the staircase whose bounding-box center is closest to the current location.
- If a nearest/closest request needs the current location and it is unknown, return -1.
- Return the selected polygon's bounding box as [minX,minY,maxX,maxY].
- Return -1 when the request is unclear, unsupported, ambiguous, or has no matching item.

Output:
""".trimIndent()
}

private fun callGemini(apiKey: String, model: String, prompt: String): String {
    val normalizedModel = model.removePrefix("models/")
    val encodedModel = URLEncoder.encode(normalizedModel, "UTF-8").replace("+", "%20")
    val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent?key=$encodedKey")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 60_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }

    val body = """
{
  "contents": [
    {
      "role": "user",
      "parts": [
        {
          "text": ${jsonString(prompt)}
        }
      ]
    }
  ],
  "generationConfig": {
    "temperature": 0,
    "responseMimeType": "text/plain"
  }
}
""".trimIndent()

    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

    val status = connection.responseCode
    val responseBody = if (status in 200..299) {
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } else {
        val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val message = extractJsonStringField(errorText, "message") ?: errorText.take(300)
        throw IllegalStateException("Gemini API returned HTTP $status: $message")
    }

    return extractJsonStringField(responseBody, "text")
        ?: throw IllegalStateException("Gemini response did not contain text.")
}

private fun normalizeModelAnswer(rawAnswer: String): String? {
    val cleaned = rawAnswer
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    val bboxMatch = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*]""")
        .find(cleaned)
    if (bboxMatch != null) {
        val values = bboxMatch.groupValues.drop(1).map { it.toInt() }
        if (values[0] <= values[2] && values[1] <= values[3]) {
            return "[${values[0]},${values[1]},${values[2]},${values[3]}]"
        }
        return null
    }

    if (Regex("""(^|\D)-1(\D|$)""").containsMatchIn(cleaned)) return "-1"
    return null
}

private fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 16)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
    }
    out.append('"')
    return out.toString()
}

private fun extractJsonStringField(json: String, field: String): String? {
    val marker = "\"$field\""
    var searchFrom = 0
    while (true) {
        val fieldIndex = json.indexOf(marker, searchFrom)
        if (fieldIndex == -1) return null

        var i = fieldIndex + marker.length
        while (i < json.length && json[i].isWhitespace()) i += 1
        if (i >= json.length || json[i] != ':') {
            searchFrom = fieldIndex + marker.length
            continue
        }
        i += 1
        while (i < json.length && json[i].isWhitespace()) i += 1
        if (i >= json.length || json[i] != '"') {
            searchFrom = fieldIndex + marker.length
            continue
        }

        return parseJsonString(json, i)
    }
}

private fun parseJsonString(json: String, quoteIndex: Int): String {
    val out = StringBuilder()
    var i = quoteIndex + 1
    while (i < json.length) {
        val ch = json[i]
        if (ch == '"') return out.toString()
        if (ch != '\\') {
            out.append(ch)
            i += 1
            continue
        }

        i += 1
        if (i >= json.length) break
        when (val escaped = json[i]) {
            '"', '\\', '/' -> out.append(escaped)
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                if (i + 4 >= json.length) break
                val code = json.substring(i + 1, i + 5).toIntOrNull(16)
                if (code != null) out.append(code.toChar())
                i += 4
            }
            else -> out.append(escaped)
        }
        i += 1
    }
    throw IllegalArgumentException("Unterminated JSON string in Gemini response.")
}
