package com.dis.fshipbot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {
    private Long id;
    private String configKey;
    private String configValue;
    private String category;
    private Instant updatedAt;
}
