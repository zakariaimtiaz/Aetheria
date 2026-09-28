package com.dis.fshipbot;

import com.dis.fshipbot.service.ChatService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

public class DegenCheckTest {

    private boolean check(String answer) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("isDegenerateAnswer", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(new ChatService(), answer);
    }

    @Test
    public void loopDetected() throws Exception {
        StringBuilder loop = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            loop.append("[4.1 General Leave Rules / 4.1.6] includes: Leave must be approved by ")
                    .append("the respective Supervisors. Leave Form need to be submitted. ");
        }
        Assertions.assertTrue(check(loop.toString()), "repetition loop must be degenerate");
    }

    @Test
    public void realWorldAnnualLoopDetected() throws Exception {
        // Exact sentence from the reported leave-types answer, repeated as observed.
        String sentence = "Employees will be entitled to a maximum of 20 days of annual leave "
                + "with full pay in a calendar year, Employee can carry forward a maximum "
                + "of 10 (ten) days unused annual leave to the succeeding year. ";
        StringBuilder loop = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            loop.append(sentence);
        }
        loop.append("Employees will "); // trailing cut-off as observed
        Assertions.assertTrue(check(loop.toString()), "reported annual loop must be degenerate");
    }

    @Test
    public void normalAnswerPasses() throws Exception {        String normal = "- Annual Leave - maximum of 20 days, carry forward 10, cap 45\n"
                + "- Casual Leave - 10 days, max 3 at a time\n"
                + "- Sick Leave - 14 days per calendar year, certificate for 3+ days\n"
                + "- Leave without Pay - maximum one month per calendar year\n";
        Assertions.assertFalse(check(normal), "normal bullets must NOT be degenerate");
    }
}
