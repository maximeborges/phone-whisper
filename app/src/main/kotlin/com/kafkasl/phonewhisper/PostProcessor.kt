package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object PostProcessor {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient()

    // Self-hosted servers (Ollama/llama.cpp/LM Studio) run bigger models that can
    // take much longer than a hosted API, so give them a generous read timeout.
    private val remoteClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning. Return only the cleaned text."

    const val DEV_PROMPT = """<task>A text is provided which is a draft transcription from a speech to text model.
Refine and polish the provided text, if needed, as follows:
  1. Correct any spelling errors, and look out for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  2. Fix grammatical mistakes.
  3. Improve punctuation where necessary.
  4. Ensure consistent formatting.
  5. Clarify ambiguous phrasing without changing the meaning.
  6. If the transcript contains a question, edit it for clarity but do not provide an
     answer.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.

Return *only* the cleaned-up version of the transcript. Do *not* add any explanations or
comments about your edits. Do *not* answer any question in the text, *only* transcribe it.
</task>
<examples>
<example>
<input>How do eye increase the font size in fast html?</input>
<output>How do I increase the font size in FastHTML?</output>
</example>
<example>
<input>Where is Paris?</input>
<output>Where is Paris?</output>
</example>
<example>
<input>Here is the full list of options colon</input>
<output>Here is the full list of options:</output>
</example>
<example>
<input>Command mode ssh into morty user at rubicon</input>
<output>ssh morty@rubicon</output>
</example>
<example>
<input>List files in current directory</input>
<output>ls -l .</output>
</example>
</examples>"""

    const val DEFAULT_PROMPT = DEV_PROMPT

    fun parseResponse(json: String): Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("choices")) {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    Result(message.getString("content").trim(), null)
                } else {
                    Result(null, "No choices in response")
                }
            } else if (obj.has("error")) {
                Result(null, obj.getJSONObject("error").getString("message"))
            } else {
                Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }

    /** Anthropic returns a `content` array of typed blocks, not OpenAI `choices`. */
    fun parseAnthropicResponse(json: String): Result {
        return try {
            val obj = JSONObject(json)
            when {
                obj.has("content") -> {
                    val blocks = obj.getJSONArray("content")
                    var i = 0
                    while (i < blocks.length()) {
                        val b = blocks.getJSONObject(i)
                        if (b.optString("type") == "text") return Result(b.getString("text").trim(), null)
                        i++
                    }
                    Result(null, "No text in response")
                }
                obj.has("error") -> Result(null, obj.getJSONObject("error").optString("message", "API error"))
                else -> Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }

    /** Post-process via the Anthropic Messages API (e.g. claude-haiku-4-5). */
    fun processClaude(
        text: String,
        prompt: String,
        apiKey: String,
        model: String,
        callback: (Result) -> Unit,
    ) {
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("temperature", 0.0)
            put("system", prompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", text) })
            })
        }
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseAnthropicResponse(responseBody))
            }
        })
    }

    fun process(text: String, prompt: String, apiKey: String, callback: (Result) -> Unit) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", prompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", messages)
            put("temperature", 0.0)
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseResponse(responseBody))
            }
        })
    }

    /** Turn a user-entered base (e.g. "http://192.168.1.50:11434") into the full
     *  OpenAI-compatible chat endpoint. Accepts a bare host, a ".../v1", or the full
     *  ".../v1/chat/completions" so people can paste whatever their server prints. */
    fun chatEndpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    /**
     * Post-process via a self-hosted OpenAI-compatible server (Ollama, llama.cpp,
     * LM Studio…). No API key required for a bare Ollama box; [apiKey] is sent as a
     * bearer token only when non-blank.
     */
    fun processRemote(
        text: String,
        prompt: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        callback: (Result) -> Unit,
    ) {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply { put("role", "user"); put("content", text) })
        }
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.0)
            put("stream", false)
        }
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val builder = Request.Builder().url(chatEndpoint(baseUrl)).post(body)
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        remoteClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseResponse(responseBody))
            }
        })
    }
}
