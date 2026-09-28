package com.dis.fshipbot.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class ContentHashUtil {

    public String calculateUrlHash(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            log.error("Error calculating URL hash", e);
            return String.valueOf(url.hashCode());
        }
    }

    public String calculateContentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            log.error("Error calculating content hash", e);
            return String.valueOf(content.hashCode());
        }
    }

    public String calculateFileHash(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            byte[] hash = digest.digest();
            return bytesToHex(hash);

        } catch (Exception e) {
            log.error("Error calculating file hash for {}", file.getName(), e);
            return String.valueOf(file.getAbsolutePath().hashCode());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}