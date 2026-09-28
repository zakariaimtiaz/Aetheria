package com.dis.fshipbot.controller;

import com.dis.fshipbot.config.PageAccessInterceptor;
import com.dis.fshipbot.model.ChatRequest;
import com.dis.fshipbot.model.ChatResponse;
import com.dis.fshipbot.service.ChatService;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @PostMapping("/ask")
    public ResponseEntity<ChatResponse> askQuestion(@Valid @RequestBody ChatRequest request) {
        log.info("Received question: {}", request.getQuestion());
        ChatResponse response = chatService.answerQuestion(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * Validates the admin date key. Retained for API clients; the admin UI posts
     * to {@code POST /admin/login} instead.
     */
    @PostMapping("/validate-key")
    public ResponseEntity<?> validateKey(@RequestBody Map<String, String> body, HttpSession session) {
        if (HomeController.isValidKey(body.get("key"))) {
            session.setAttribute(PageAccessInterceptor.SESSION_KEY, true);
            log.info("Admin access granted via key validation");
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.ok(Map.of("valid", false));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        log.info("Session invalidated via logout");
        return ResponseEntity.ok(Map.of("status", "logged out"));
    }
}