package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longexposure.llm.LlamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Orchestrates the two-pass + verify model for one event end-to-end.
 * Pure logic — no Temporal, no JDBC. The activity layer wraps this.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>{@link BlueprintExtractor} → blueprint JSON (LLM call #1)
 *   <li>{@link ProseRenderer} → prose string (LLM call #2)
 *   <li>{@link GroundingVerifier} → pass/fail + mismatches (pure code)
 * </ol>
 *
 * <p>The verifier failing does NOT abort the pipeline — the prose is
 * still produced and stored; the {@code verifier_passed} column flags
 * problems for downstream filtering (e.g. don't publish failed
 * narrations to the public API).
 */
public final class NarrationPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(NarrationPipeline.class);

    private final BlueprintExtractor extractor;
    private final ProseRenderer      renderer;
    private final GroundingVerifier  verifier;
    private final String             modelId;

    public NarrationPipeline(final LlamaClient llama, final String modelId) {
        this.extractor = new BlueprintExtractor(llama);
        this.renderer  = new ProseRenderer(llama);
        this.verifier  = new GroundingVerifier();
        this.modelId   = modelId;
    }

    /**
     * Run the full pipeline against one selected event.
     */
    public Result narrate(final NarrationInput input) {
        long t0 = System.nanoTime();

        // Pass 1: extract blueprint
        JsonNode blueprint;
        try {
            blueprint = extractor.extract(input.scorerId(), input.symbol(), input.breakdown());
        } catch (Exception e) {
            throw new RuntimeException("extract failed for selected_id=" + input.selectedId(), e);
        }

        // Pass 2: render prose
        String prose;
        try {
            prose = renderer.render(blueprint);
        } catch (Exception e) {
            throw new RuntimeException("render failed for selected_id=" + input.selectedId(), e);
        }

        // Pass 3 (pure code): verify
        GroundingVerifier.Result verify = verifier.verify(prose, blueprint, input.breakdown());
        if (!verify.passed()) {
            LOG.warn("verifier failed  selected_id={} scorer={} symbol={} mismatches={}",
                    input.selectedId(), input.scorerId(), input.symbol(), verify.mismatches());
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return new Result(
                eventHash(input),
                blueprint,
                prose,
                verify,
                modelId,
                elapsedMs);
    }

    /**
     * Deterministic event hash for narration cache keying. Combines the
     * scorer id, the breakdown JSON, and the prompt version strings so
     * that re-running narration on identical input produces the same
     * hash (cache hit) and changing the prompt invalidates it.
     */
    public byte[] eventHash(final NarrationInput input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input.scorerId().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(input.breakdown().toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(BlueprintExtractor.PROMPT_VERSION.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(ProseRenderer.PROMPT_VERSION.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Input to one narration. Mirrors a {@code selected_events} row. */
    public record NarrationInput(
            long       selectedId,
            String     tradingDate,    // ISO date string, passed through
            String     scorerId,
            String     symbol,
            Instant    ts,
            double     score,
            JsonNode   breakdown
    ) {}

    /** Result of one narration. */
    public record Result(
            byte[]                   eventHash,
            JsonNode                 blueprint,
            String                   prose,
            GroundingVerifier.Result verify,
            String                   modelId,
            long                     elapsedMs
    ) {}
}
