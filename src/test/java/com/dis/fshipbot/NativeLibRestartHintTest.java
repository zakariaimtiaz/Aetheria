package com.dis.fshipbot;

import com.dis.fshipbot.config.VectorStoreConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The "BGE native library already loaded" failure can only be fixed by
 * restarting the Tomcat service, so the message must name the right command.
 *
 * <p>It previously hard-coded the Windows {@code net stop/start} form while the
 * real deployment is Linux — the operator was told to run a command that does not
 * exist, with the actual fix sitting in the same message. These tests pin the
 * hint per platform so a redeploy on either host is actionable.
 */
public class NativeLibRestartHintTest {

    @Test
    void linuxGetsASystemdHint() {
        String hint = VectorStoreConfig.restartHint("Linux");
        Assertions.assertTrue(hint.contains("systemctl restart"),
                    "the Linux deployment must be told to use systemd: " + hint);
        Assertions.assertFalse(hint.contains("net stop"),
                "the Windows command does not exist on Linux: " + hint);
    }

    @Test
    void windowsGetsTheNetCommand() {
        String hint = VectorStoreConfig.restartHint("Windows 10");
        Assertions.assertTrue(hint.contains("net stop"), hint);
        Assertions.assertTrue(hint.contains("net start"), hint);
    }

    @Test
    void macAndBsdGetASystemdStyleHint() {
        Assertions.assertTrue(VectorStoreConfig.restartHint("Mac OS X").contains("systemctl"));
    }

    @Test
    void unknownPlatformStillNamesBothOptions() {
        // Better to offer both than to guess wrong and be un-actionable.
        String hint = VectorStoreConfig.restartHint("Plan 9");
        Assertions.assertTrue(hint.contains("systemctl") && hint.contains("net stop"), hint);
    }

    @Test
    void nullPlatformDoesNotThrow() {
        // A diagnostics hint must never itself break startup.
        Assertions.assertDoesNotThrow(() -> VectorStoreConfig.restartHint(null));
    }

    @Test
    void theRunningPlatformGetsANonEmptyHint() {
        Assertions.assertFalse(VectorStoreConfig.restartHint().isBlank());
    }
}
