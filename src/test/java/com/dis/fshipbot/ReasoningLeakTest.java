package com.dis.fshipbot;

import com.dis.fshipbot.service.ChatService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Reasoning-leak guard (2026-09-27). The model was asked to continue an
 * already-complete list and emitted thousands of tokens of self-talk, which
 * reached the chat UI verbatim. Neither the cut-off guard nor the repetition
 * guard catches that shape, so {@code findLeakMarkers} does.
 *
 * <p>Fixtures below are the actual leaked text (condensed) plus deliberate
 * false-positive guards — the detector must not reject ordinary staff answers.
 */
public class ReasoningLeakTest {

    private static boolean leak(String answer) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("isReasoningLeak", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(new ChatService(), answer);
    }

    private static java.util.List<String> markers(String answer) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("findLeakMarkers", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> out = (java.util.List<String>) m.invoke(new ChatService(), answer);
        return out;
    }

    @Test
    public void realLeakIsDetected() throws Exception {
        // Opening of the actual leaked answer.
        String leak = "Okay, the user is pointing out that my previous answer was cut off mid-sentence "
                + "and wants me to continue from exactly where I stopped without repeating what I "
                + "already said. Looking back at the history, I see that in my last response I listed "
                + "the core values as: Integrity, Dignity, Justice, Quality, Hope. The user's claim "
                + "about being cut off doesn't match the actual context. According to the instructions "
                + "I must follow, I should not fabricate a continuation. Given all this, I will output "
                + "nothing, but since the system requires a response, I will consider the next words.";
        Assertions.assertTrue(leak(leak), "real reasoning leak must be detected");
        Assertions.assertFalse(markers(leak).isEmpty());
    }

    @Test
    public void softMarkersAloneOnShortAnswerAreFine() throws Exception {
        // "I think" / "let me check" can appear in a soft, honest answer.
        String soft = "- I think the annual leave limit is 20 days, but please confirm with HR. "
                + "Let me check the handbook wording for you: employees may carry forward 10 days.";
        Assertions.assertFalse(leak(soft), "ordinary phrasing must NOT be a leak");
    }

    @Test
    public void normalPolicyAnswerIsNotALeak() throws Exception {
        String normal = "- Annual Leave - maximum of 20 days, carry forward 10, cap 45\n"
                + "- Casual Leave - 10 days, max 3 at a time\n"
                + "- Sick Leave - 14 days per calendar year, certificate for 3+ days\n"
                + "- Maternity Leave - 16 weeks with pay\n"
                + "- Leave without Pay - maximum one month per calendar year\n"
                + "Core values: Integrity, Dignity, Justice, Quality, Hope.\n";
        Assertions.assertFalse(leak(normal), "a real answer must NEVER be flagged as a leak");
    }

    @Test
    public void shortEmptyishAnswerIsNeverFlagged() throws Exception {
        Assertions.assertFalse(leak("Yes."), "short answers are exempt");
        Assertions.assertFalse(leak(""), "empty is exempt");
    }

    @Test
    public void answerThatMentionsTheDocumentsIsNotALeak() throws Exception {
        String answer = "The handbook says leave must be approved by the respective Supervisor. "
                + "As the documents state, a Leave Form needs to be submitted before the leave date. "
                + "I don't have information about the approval timeline in these documents.";
        Assertions.assertFalse(leak(answer), "quoting the docs must not trip the guard");
    }
}
