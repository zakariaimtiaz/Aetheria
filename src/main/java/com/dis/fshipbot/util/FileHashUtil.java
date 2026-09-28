package com.dis.fshipbot.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;

@Slf4j
@Component
public class FileHashUtil {

    public String calculateSHA256(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream fis = Files.newInputStream(filePath)) {
                byte[] byteArray = new byte[1024];
                int bytesCount;

                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
            }

            byte[] bytes = digest.digest();

            // Convert to hex
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("Error calculating file hash for: {}", filePath, e);
            return null;
        }
    }

    public long getLastModified(Path filePath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            return attrs.lastModifiedTime().toMillis();
        } catch (Exception e) {
            log.error("Error getting last modified time for: {}", filePath, e);
            return 0;
        }
    }
}