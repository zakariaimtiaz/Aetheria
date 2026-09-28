package com.dis.fshipbot;

import com.dis.fshipbot.config.VectorStoreConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A PostgreSQL auth failure looks identical whether the password is wrong, the
 * variable never reached the JVM, or the hostname resolved to a different
 * server. That produced a 90-line stack trace with no diagnostic value.
 *
 * <p>These tests pin the connection-target parsing used in the startup log and
 * in the wrapped error message. If the URL parser silently returns the wrong
 * host, the diagnostic points the operator at the wrong machine — worse than no
 * hint at all.
 */
public class JdbcTargetDiagnosticTest {

    private static final String URL =
            "jdbc:postgresql://devs2.apps.friendship.ngo:5432/fship_ai_1_1"
                    + "?currentSchema=fship_ai&options=-c%20search_path=fship_ai";

    @Test
    void hostIsExtractedFromTheJdbcUrl() {
        Assertions.assertEquals("devs2.apps.friendship.ngo", VectorStoreConfig.hostOf(URL));
    }

    @Test
    void databaseIsExtractedWithoutTheQueryString() {
        Assertions.assertEquals("fship_ai_1_1", VectorStoreConfig.databaseOf(URL),
                "the schema/currentSchema parameters must not end up in the suggested "
                        + "psql -d argument");
    }

    @Test
    void targetOmitsTheQueryString() {
        String target = VectorStoreConfig.describeTarget(URL);
        Assertions.assertTrue(target.contains("devs2.apps.friendship.ngo:5432/fship_ai_1_1"));
        Assertions.assertFalse(target.contains("search_path"),
                "the query string is noise in a log line and is stripped");
    }

    @Test
    void targetNeverLeaksEmbeddedCredentials() {
        // Defensive: this project has no credentials in the URL, but a future
        // ?user=...&password=... must never reach a log line or an exception.
        String leaky = "jdbc:postgresql://admin:hunter2@db.internal:5432/fship_ai_1_1?ssl=true";

        String target = VectorStoreConfig.describeTarget(leaky);
        Assertions.assertFalse(target.contains("hunter2"), "password leaked into: " + target);
        Assertions.assertTrue(target.contains("<redacted>"), target);
    }

    @Test
    void hostOfStillWorksWhenCredentialsArePresentInTheUrl() {
        String leaky = "jdbc:postgresql://admin:hunter2@db.internal:5432/fship_ai_1_1";
        Assertions.assertEquals("db.internal", VectorStoreConfig.hostOf(leaky),
                "a wrong host here would point the operator at the wrong server");
        Assertions.assertEquals("fship_ai_1_1", VectorStoreConfig.databaseOf(leaky));
    }

    @Test
    void urlWithoutAPortIsHandled() {
        String noPort = "jdbc:postgresql://localhost/fship_ai_1_1";
        Assertions.assertEquals("localhost", VectorStoreConfig.hostOf(noPort));
        Assertions.assertEquals("fship_ai_1_1", VectorStoreConfig.databaseOf(noPort));
    }

    @Test
    void nullUrlDoesNotThrow() {
        // Diagnostics must never be the thing that breaks startup.
        Assertions.assertDoesNotThrow(() -> VectorStoreConfig.describeTarget(null));
        Assertions.assertDoesNotThrow(() -> VectorStoreConfig.hostOf(null));
        Assertions.assertDoesNotThrow(() -> VectorStoreConfig.databaseOf(null));
    }
}
