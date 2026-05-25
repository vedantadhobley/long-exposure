package com.longexposure.llm;

/**
 * Decoder sampling parameters for one chat completion. Wraps the six
 * knobs Qwen3.5's model card publishes recommendations for. Two named
 * presets cover both narration passes; case-by-case construction is
 * available via {@link #of(double, double, int, double, double, double)}.
 *
 * <p><b>Why these specific knobs:</b> the Qwen3.5 model card publishes
 * per-mode sampling guidance at
 * <a href="https://huggingface.co/Qwen/Qwen3.5-122B-A10B">Qwen3.5-122B-A10B</a>.
 * Their values for "Instruct (non-thinking) mode for general tasks" are
 * what {@link #RENDER} encodes verbatim. {@link #EXTRACT} starts from
 * the same base but pins temperature low and drops the presence penalty
 * to zero — extraction wants deterministic JSON, not prose variety.
 *
 * <p><b>Thinking vs instruct:</b> we use instruct mode (no {@code <think>}
 * tags, no chain-of-thought). The scorer does the reasoning; the LLM
 * just renders. Thinking mode would 10× cost for no quality benefit on
 * a "render this known answer" task.
 *
 * @param temperature       0.0–1.0+; lower = more deterministic
 * @param topP              nucleus sampling cutoff; 1.0 disables
 * @param topK              top-k sampling cutoff; 0 disables
 * @param minP              minimum probability cutoff; 0.0 disables
 * @param presencePenalty   −2.0..+2.0; positive values discourage tokens
 *                          already in the context (reduces repetition)
 * @param repetitionPenalty 1.0 disables; >1.0 penalizes recent tokens.
 *                          Qwen recommends 1.0 for both modes — we keep
 *                          presence_penalty as the sole repetition guard.
 */
public record SamplingParams(
        double temperature,
        double topP,
        int topK,
        double minP,
        double presencePenalty,
        double repetitionPenalty) {

    /**
     * Tight params for pass-1 blueprint extraction. Same Qwen base
     * (top_p=0.8, top_k=20, min_p=0) but with temperature pinned low
     * and presence penalty zero — we want deterministic JSON, not
     * prose variety. Repetition penalty 1.0 = disabled.
     */
    public static final SamplingParams EXTRACT = new SamplingParams(
            0.1, 0.8, 20, 0.0, 0.0, 1.0);

    /**
     * Qwen3.5 model-card "Instruct mode for general tasks" — verbatim.
     * Used for pass-2 prose rendering where some variety is wanted.
     */
    public static final SamplingParams RENDER = new SamplingParams(
            0.7, 0.8, 20, 0.0, 1.5, 1.0);

    /**
     * Qwen3.5 model-card "Instruct mode for reasoning tasks" — verbatim.
     * Used for the SYNTHESIZE stage (daily themes paragraph across all
     * narrations + interpretations). The task is genuinely reasoning-
     * shaped: the model has to find cross-event patterns spanning ~164
     * narration paragraphs, which benefits from higher sampling variety
     * + a strong presence penalty against narrow repetition.
     *
     * <p>Knob differences from RENDER:
     * <ul>
     *   <li>temperature 1.0 (vs 0.7) — broader exploration of phrasings
     *   <li>top_p 1.0 (vs 0.8) — no nucleus cutoff
     *   <li>top_k 40 (vs 20) — wider per-step candidate pool
     *   <li>presence_penalty 2.0 (vs 1.5) — stronger anti-narrow-repetition
     * </ul>
     */
    public static final SamplingParams SYNTHESIZE = new SamplingParams(
            1.0, 1.0, 40, 0.0, 2.0, 1.0);

    /**
     * Weekly AGGREGATE stage (themes across a week of daily syntheses).
     * Same Qwen "Instruct mode for reasoning tasks" knobs as
     * {@link #SYNTHESIZE} — AGGREGATE is the same shape of task one level
     * up (find cross-day patterns across ~5 daily-theme paragraphs), so it
     * wants the same higher-variety + strong-presence-penalty profile.
     * Named separately so call sites read as the stage they belong to.
     */
    public static final SamplingParams AGGREGATE = SYNTHESIZE;

    public static SamplingParams of(double t, double tp, int tk, double mp,
                                    double pp, double rp) {
        return new SamplingParams(t, tp, tk, mp, pp, rp);
    }
}
