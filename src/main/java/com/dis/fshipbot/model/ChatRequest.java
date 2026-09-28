package com.dis.fshipbot.model;

import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    @NotBlank(message = "Question cannot be empty")
    private String question;
    private boolean includeSources = true;
    private String sessionId;
}