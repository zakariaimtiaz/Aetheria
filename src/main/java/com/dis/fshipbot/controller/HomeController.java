package com.dis.fshipbot.controller;

import com.dis.fshipbot.config.PageAccessInterceptor;
import com.dis.fshipbot.service.AppConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Controller
public class HomeController {

    @Autowired
    private AppConfigService configService;

    // ------------------ Pages ------------------

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/chat")
    public String chat() {
        return "index";
    }

    /** Login screen for the admin panel. Reachable without a session grant. */
    @GetMapping("/admin/login")
    public String adminLogin() {
        return "admin-login";
    }

    /**
     * Validates the date key and, on success, sends the browser to the first
     * admin page. This replaces the old chat-page eye-icon entry point: the chat
     * page has no admin affordance at all now.
     */
    @PostMapping("/admin/login")
    public String adminLoginSubmit(@RequestParam("key") String key,
                                   HttpSession session,
                                   Model model) {
        if (isValidKey(key)) {
            session.setAttribute(PageAccessInterceptor.SESSION_KEY, true);
            return "redirect:/admin/prompts";
        }
        model.addAttribute("error", "Invalid key. The key is today's date as YYYYMMDD.");
        return "admin-login";
    }

    /**
     * The admin key is the current date formatted as {@code yyyyMMdd}.
     * Kept in one place so the login form and {@code /api/validate-key} agree.
     */
    public static String todayKey() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    public static boolean isValidKey(String key) {
        return key != null && todayKey().equals(key.trim());
    }

    // ------------------ XML API (DB only) ------------------

    @GetMapping(value = "/app-config.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String getXml() {
        return configService.buildXml();
    }

    @PostMapping(value = "/app-config.xml", consumes = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String saveXml() {
        return "Use /admin/settings to manage settings";
    }

    @GetMapping("/config-mode")
    @ResponseBody
    public String getMode() {
        return "EDITABLE";
    }
}
