package com.dis.fshipbot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One group of prompts as the admin page needs it: the group id, its display
 * title, and the prompts belonging to it.
 *
 * <p>Built in the controller rather than resolved in the template. Looking a
 * group up with {@code th:with="${map.get(id)}"} does not work here: Thymeleaf
 * evaluates {@code th:if} BEFORE {@code th:with}, so the condition always saw a
 * null map result and silently rendered an empty page.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptSection {
    private String id;
    private String title;
    private String description;
    private List<AppPrompt> prompts;
}
