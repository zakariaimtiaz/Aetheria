package com.dis.fshipbot;

import com.dis.fshipbot.service.ChatService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Cut-off guard (2026-09-28). "What are Friendship's core values?" returned a
 * complete four-item bulleted list ending "…- Hope" with finish=STOP. The
 * character-only cut-off test saw no terminal punctuation, declared the answer
 * truncated, and asked the model to continue an already-complete list. The forced
 * continuation produced 921 chars of unsupported text, grounding rejected it
 * (noul=0.090), and a straightforward question surfaced the "I found some
 * potentially relevant documents" safe fallback.
 *
 * <p>The fixtures below are the actual observed shapes plus deliberate
 * false-positive guards — a genuine mid-word truncation must still be caught,
 * because that is what the guard was built for (2026-09-27, "Friendship Colours
 * of the Ch").
 */
public class CutOffGuardTest {

    private static boolean cutOff(String answer) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("isCutOff", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(new ChatService(), answer);
    }

    @Test
    public void completeBulletedListIsNotCutOff() throws Exception {
        // Verbatim tail of the real 87-char answer that triggered the bug.
        String answer = "Friendship's core values are:\n"
                + "- Dignity\n"
                + "- Justice\n"
                + "- Quality\n"
                + "- Hope";
        Assertions.assertFalse(cutOff(answer),
                "a list ending on its last item is a deliberate stop, not a truncation");
    }

    @Test
    public void completeNumberedListIsNotCutOff() throws Exception {
        String answer = "The leave entitlements are as follows:\n"
                + "1. Annual Leave - 20 days, carry forward 10\n"
                + "2. Casual Leave - 10 days, max 3 at a time\n"
                + "3. Maternity Leave - 16 weeks with pay";
        Assertions.assertFalse(cutOff(answer), "numbered lists end the same way");
    }

    @Test
    public void completeLetteredListIsNotCutOff() throws Exception {
        String answer = "The escalation path runs:\n"
                + "a. Line Manager\n"
                + "b. Head of Department\n"
                + "c. Human Resources Committee";
        Assertions.assertFalse(cutOff(answer), "lettered lists end the same way");
    }

    @Test
    public void realMidWordTruncationIsStillCaught() throws Exception {
        // The 2026-09-27 case the guard was built for.
        String answer = "Friendship's organisational colours, drawn from the brand "
                + "guidelines, are used across all programme collateral and "
                + "communications materials to express the identity of the "
                + "organisation in a consistent, recognisable and instantly "
                + "identifiable way across every single touchpoint that we ever "
                + "produce for the Friendship Colours of the Ch";
        Assertions.assertTrue(cutOff(answer), "a genuine mid-word cut must still be detected");
    }

    @Test
    public void truncationEndingInsideAListItemIsStillCaught() throws Exception {
        // A single trailing bullet with no preceding list item is still suspect.
        String answer = "Friendship works across Bangladesh in these regions:\n"
                + "- Dhaka and Chattogram, where the largest num";
        Assertions.assertTrue(cutOff(answer), "one lone bullet is not a list boundary");
    }

    @Test
    public void completeProseAnswerIsNotCutOff() throws Exception {
        String answer = "The handbook says leave must be approved by the respective "
                + "Supervisor at least three days in advance.";
        Assertions.assertFalse(cutOff(answer), "terminal punctuation means complete");
    }

    @Test
    public void shortAnswerIsNeverFlagged() throws Exception {
        Assertions.assertFalse(cutOff("Yes."), "short answers are exempt");
        Assertions.assertFalse(cutOff(""), "empty is exempt");
        Assertions.assertFalse(cutOff(null), "null is exempt");
    }
}
