package com.dis.fshipbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes shared by every page, so branding is never hard-coded in a
 * template.
 *
 * <p>{@code application.title} / {@code application.version} in
 * application.properties are Maven-filtered from the POM
 * ({@code @project.name@} / {@code @project.version@}), so the displayed name
 * follows the artifact rather than a string someone typed into an HTML file.
 *
 * <p>Applies to {@code HomeController} (the admin login page) and both admin
 * controllers, which is why it lives here instead of being repeated per
 * controller.
 */
@ControllerAdvice
public class GlobalViewAdvice {

    private final String appName;
    private final String appVersion;

    public GlobalViewAdvice(
            @Value("${application.title:FShipBot}") String appName,
            @Value("${application.version:}") String appVersion) {
        this.appName = appName;
        this.appVersion = appVersion;
    }

    @ModelAttribute("appName")
    public String appName() {
        return appName;
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }
}
