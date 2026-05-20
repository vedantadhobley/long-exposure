package com.longexposure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * Minimal OpenAI-compatible chat client for {@code llama-large.joi}
 * (and any other llama.cpp-served endpoint that speaks the standard
 * {@code /v1/chat/completions} schema).
 *
 * <p>v1: synchronous, single-turn chat. Streaming and tool calls are
 * deliberately out of scope — see {@code docs/scoring-and-narration.md}
 * for why narration uses the pre-fetched-breakdown model rather than
 * tool-calling.
 */
public final class LlamaClient {

    private static final Logger LOG = LoggerFactory.getLogger(LlamaClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /**
     * Hard cap on concurrent in-flight chat calls. `llama-large.joi` is a
     * single-GPU local model — throughput collapses above 2 concurrent
     * decode streams. JVM-wide semaphore so this can't be violated even
     * across activity instances. See @docs/scoring-and-narration.md
     * "Operational constraints".
     */
    private static final int MAX_CONCURRENT_CALLS = 2;
    private static final Semaphore CONCURRENCY_GATE = new Semaphore(MAX_CONCURRENT_CALLS, true);

    private final OkHttpClient http;
    private final ObjectMapper json;
    private final String endpoint;     // e.g. "http://llama-large.joi/v1"
    private final String model;        // e.g. "Qwen3.5-122B-A10B"

    /**
     * @param endpoint  e.g. {@code http://llama-large.joi/v1}. Trailing slash optional.
     * @param model     model id as reported by {@code GET /v1/models}.
     */
    public LlamaClient(final String endpoint, final String model) {
        this(endpoint, model, defaultHttpClient(), new ObjectMapper());
    }

    public LlamaClient(final String endpoint, final String model,
                       final OkHttpClient http, final ObjectMapper json) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.model    = model;
        this.http     = http;
        this.json     = json;
    }

    /**
     * Single-turn chat completion with explicit sampling parameters.
     * Two ready-made presets live on {@link SamplingParams}:
     * {@link SamplingParams#EXTRACT} (tight, for JSON) and
     * {@link SamplingParams#RENDER} (Qwen-recommended instruct-general).
     *
     * @param systemPrompt  system message (instructions / role / constraints)
     * @param userPrompt    user message (the actual input)
     * @param params        decoder sampling knobs
     */
    public String chat(final String systemPrompt, final String userPrompt, final SamplingParams params) {
        ObjectNode body = buildBody(systemPrompt, userPrompt, params);
        Request req = new Request.Builder()
                .url(endpoint + "/chat/completions")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        long t0 = System.nanoTime();
        boolean acquired = false;
        try {
            CONCURRENCY_GATE.acquire();
            acquired = true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM gate interrupted", ie);
        }
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = bodyAsString(resp);
                throw new RuntimeException("LLM " + resp.code() + ": " + errBody);
            }
            String respJson = bodyAsString(resp);
            JsonNode root = json.readTree(respJson);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("LLM response had no choices: " + respJson);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            int promptToks    = root.path("usage").path("prompt_tokens").asInt(-1);
            int completionTks = root.path("usage").path("completion_tokens").asInt(-1);
            LOG.info("LLM ok  elapsed_ms={} prompt_tokens={} completion_tokens={} content_chars={} concurrent_in_flight={}",
                    ms, promptToks, completionTks, content.length(),
                    MAX_CONCURRENT_CALLS - CONCURRENCY_GATE.availablePermits());
            return content;
        } catch (IOException ioe) {
            throw new RuntimeException("LLM HTTP failed", ioe);
        } finally {
            if (acquired) CONCURRENCY_GATE.release();
        }
    }

    /** Convenience: defaults to {@link SamplingParams#RENDER} (Qwen instruct-general). */
    public String chat(final String systemPrompt, final String userPrompt) {
        return chat(systemPrompt, userPrompt, SamplingParams.RENDER);
    }

    private ObjectNode buildBody(final String systemPrompt, final String userPrompt, final SamplingParams p) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        // OpenAI-compatible fields llama.cpp recognizes. repetition_penalty
        // is sent only when != 1.0 (server treats 1.0 as disabled but we
        // skip transmitting to keep the wire payload clean).
        body.put("temperature", p.temperature());
        body.put("top_p", p.topP());
        body.put("top_k", p.topK());
        body.put("min_p", p.minP());
        body.put("presence_penalty", p.presencePenalty());
        if (p.repetitionPenalty() != 1.0) {
            body.put("repeat_penalty", p.repetitionPenalty());
        }
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", userPrompt);
        return body;
    }

    private static String bodyAsString(final Response resp) throws IOException {
        ResponseBody body = resp.body();
        return body == null ? "" : body.string();
    }

    /** Default client tuned for slow generation (Qwen3.5-122B at ~23 tok/sec). */
    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(5))
                .callTimeout(Duration.ofMinutes(10))
                .build();
    }

    // ─── Static helpers ──────────────────────────────────────────────────────

    /** Read endpoint + model from env vars, with sensible defaults. */
    public static LlamaClient fromEnv() {
        String url   = System.getenv().getOrDefault("LLAMA_URL",   "http://llama-large.joi/v1");
        String model = System.getenv().getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");
        return new LlamaClient(url, model);
    }
}
