package com.dis.fshipbot;

import com.dis.fshipbot.util.PromptDefaults;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The Jev grounding judge was rejecting ordinary answers.
 *
 * <p>Observed: "Tell about Friendship NGO?" retrieved 20 well-scored chunks
 * (Jev rerank noul 0.97), the model wrote a 3472-character answer, and the
 * grounding judge returned noul=0.090 against a 0.6 threshold. The answer was
 * discarded and replaced with a fallback. Two independent defects caused it,
 * both of which are silent — no exception, just a worse product.
 *
 * <p>1. The judge was shown 2000 of the 3472 characters, so it graded a
 *    fragment while the user saw the whole text (and the unjudged tail was
 *    shown either way).
 * 2. The seeded criteria were not complements: "true" required EVERY fact to be
 *    supported while "false" fired on ANY unsupported fact. That is a universal
 *    vs existential mismatch, which makes the verdict a function of answer
 *    LENGTH — the longer the answer, the more certain the reject. A broad
 *    question gets a broad answer, so simple questions were the ones failing.
 */
public class GroundingJudgeTest {

    @Test
    void trueCriteriaDoesNotDemandEveryFact() {
        String trueText = PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE);

        // A universal quantifier over the answer's facts is what made long
        // answers unpassable. Its return is the length bias.
        for (String universal : new String[]{
                "every fact", "all facts", "every single", "each and every"}) {
            Assertions.assertFalse(trueText.toLowerCase().contains(universal),
                    "the true criteria contains '" + universal + "', which grades answer "
                            + "LENGTH rather than grounding: " + trueText);
        }
    }

    @Test
    void trueCriteriaGradesTheAnswerAsAWhole() {
        String trueText = PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE).toLowerCase();
        Assertions.assertTrue(trueText.contains("as a whole"),
                "the true criteria must say it grades the answer as a whole");
    }

    @Test
    void falseCriteriaIsTheComplementOfTrue() {
        String trueText = PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE).toLowerCase();
        String falseText = PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_FALSE).toLowerCase();

        // Both sides must agree that synthesis is supported, otherwise the judge
        // gets contradictory signals and collapses toward false.
        for (String allowance : new String[]{"summar", "reword", "reorganiz"}) {
            Assertions.assertTrue(trueText.contains(allowance),
                    "the true criteria must allow " + allowance + "y");
            Assertions.assertTrue(falseText.contains(allowance),
                    "the false criteria must allow " + allowance + "y, or it contradicts true");
        }
    }

    @Test
    void falseCriteriaStillCatchesFabrication() {
        // The leniency above must not become "always pass": inventing or
        // contradicting the context is still the thing being guarded against.
        String falseText = PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_FALSE).toLowerCase();
        Assertions.assertTrue(falseText.contains("fabricat") || falseText.contains("contradict"),
                "the false criteria must still name fabrication or contradiction: " + falseText);
    }

    @Test
    void judgeCanSeeAWholeAnswer() {
        int cap = (Integer) ReflectionTestUtils.getField(
                com.dis.fshipbot.service.JevRerankService.class, "MAX_ANSWER_CHARS");

        Assertions.assertTrue(cap >= 4000,
                "MAX_ANSWER_CHARS=" + cap + " truncates a normal 3-4k answer, so the judge "
                        + "grades a fragment while the user sees the whole text");
    }

    @Test
    void answerCapCoversAnyRealisticAnswer() {
        int answerCap = (Integer) ReflectionTestUtils.getField(
                com.dis.fshipbot.service.JevRerankService.class, "MAX_ANSWER_CHARS");
        int contextCap = (Integer) ReflectionTestUtils.getField(
                com.dis.fshipbot.service.JevRerankService.class, "MAX_CONTEXT_CHARS");

        // The answer is a synthesis OF the context, so it is naturally much
        // shorter — it does not need to reach the context cap. What it must never
        // do is truncate an answer the model can actually produce. The observed
        // regression was a 3472-char answer against a 2000 cap.
        Assertions.assertTrue(answerCap >= contextCap / 2,
                "the answer cap (" + answerCap + ") is too tight relative to the context cap ("
                        + contextCap + "); a full-retrieval answer will be truncated");
    }
}
