package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlin.concurrent.thread

/**
 * On-device transcript cleanup with a small LLM (Gemma-3 1B) via MediaPipe.
 * The engine is loaded once and cached (mapping the ~500 MB model is expensive).
 * Each cleanup runs in a fresh session with greedy, deterministic decoding so it
 * edits faithfully instead of rewriting/hallucinating.
 */
object LocalCleanup {
    private const val TAG = "LocalCleanup"
    private const val MAX_TOKENS = 2048

    /** Concise, language-preserving default — a 1B model does far better with this
     *  than the large cloud "dev" prompt (which confuses it). Used when the user
     *  hasn't set a custom prompt. */
    const val DEFAULT_PROMPT =
        "Reply in EXACTLY the same language as the input text. NEVER translate. " +
        "If the input is French, the output must be French; if English, English. " +
        "Your only task: add correct punctuation and capitalization (capitalize each " +
        "sentence, end each with . ? or !) and fix obvious speech-to-text spelling " +
        "errors, keeping the same words and meaning. Do not add, remove, explain or " +
        "answer anything. Output ONLY the corrected text."

    @Volatile private var engine: LlmInference? = null
    @Volatile private var loadedPath: String? = null

    fun isReady(ctx: Context) = ModelDownloader.isCleanupInstalled(ctx)

    /** Load the model in the background so the first cleanup isn't slow. */
    fun prewarm(ctx: Context) {
        if (!isReady(ctx) || engine != null) return
        thread { runCatching { engine(ctx) } }
    }

    @Synchronized
    private fun engine(ctx: Context): LlmInference {
        val path = ModelDownloader.cleanupModelFile(ctx).absolutePath
        engine?.let { if (loadedPath == path) return it }
        engine?.close(); engine = null

        fun build(backend: LlmInference.Backend) = LlmInference.createFromOptions(
            ctx.applicationContext,
            LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .setPreferredBackend(backend)
                .build(),
        )
        // GPU is much faster for prefill; fall back to CPU if it can't initialize.
        val e = try {
            build(LlmInference.Backend.GPU).also { Log.i(TAG, "cleanup model loaded (GPU)") }
        } catch (gpu: Throwable) {
            Log.w(TAG, "GPU backend unavailable (${gpu.message}); falling back to CPU")
            try {
                build(LlmInference.Backend.CPU).also { Log.i(TAG, "cleanup model loaded (CPU)") }
            } catch (cpu: Throwable) {
                // A corrupt/truncated .task ("Unable to open zip archive") can't be
                // loaded on any backend — delete it so it's re-downloaded rather than
                // failing forever and silently falling back to raw text.
                val corrupt = listOf(gpu.message, cpu.message).any { it?.contains("zip", true) == true }
                if (corrupt) {
                    runCatching { java.io.File(path).delete() }
                    Log.e(TAG, "removed corrupt cleanup model: $path")
                }
                throw cpu
            }
        }
        engine = e
        loadedPath = path
        return e
    }

    /**
     * Clean up [text]. Blocking — call from a background thread. Returns null on
     * failure so the caller can fall back to the raw text.
     */
    fun process(ctx: Context, text: String, prompt: String): String? = try {
        val session = LlmInferenceSession.createFromOptions(
            engine(ctx),
            LlmInferenceSessionOptions.builder()
                .setTopK(1)          // greedy
                .setTemperature(0f)  // deterministic
                .setRandomSeed(0)
                .build(),
        )
        session.use {
            it.addQueryChunk(formatPrompt(ctx, prompt, text))
            val t0 = System.currentTimeMillis()
            val out = it.generateResponse()
            Log.i(TAG, "cleanup done in ${System.currentTimeMillis() - t0}ms")
            out?.trim()?.takeIf { r -> r.isNotBlank() }
        }
    } catch (e: Throwable) {
        Log.e(TAG, "cleanup failed: ${e.message}", e)
        null
    }

    /** Wrap the instruction + text in the selected model's chat template. Using the
     *  wrong family's template makes the model ignore instructions and never emit a
     *  stop token (runs to maxTokens → slow + wrong-language output). */
    private fun formatPrompt(ctx: Context, prompt: String, text: String): String {
        val family = ModelDownloader.cleanupModelById(ModelDownloader.selectedCleanupId(ctx))?.family
        return when (family) {
            "qwen" ->
                "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\n$prompt\n\n$text<|im_end|>\n<|im_start|>assistant\n"
            else -> // gemma
                "<start_of_turn>user\n$prompt\n\n$text<end_of_turn>\n<start_of_turn>model\n"
        }
    }

    @Synchronized
    fun close() {
        engine?.close(); engine = null; loadedPath = null
    }
}
