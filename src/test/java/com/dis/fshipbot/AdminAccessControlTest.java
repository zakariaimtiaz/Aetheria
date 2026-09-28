package com.dis.fshipbot;

import com.dis.fshipbot.config.PageAccessInterceptor;
import com.dis.fshipbot.controller.HomeController;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * The admin panel gate.
 *
 * <p>Two things are pinned here because both failures are silent in production:
 * a page that opens without a grant (unauthenticated admin), and a page that
 * redirects when it has one (admin locked out of their own panel).
 */
public class AdminAccessControlTest {

    private final PageAccessInterceptor interceptor = new PageAccessInterceptor();

    private MockHttpSession grantedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PageAccessInterceptor.SESSION_KEY, true);
        return session;
    }

    private boolean allowed(MockHttpServletRequest request) throws Exception {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    public void loginPageIsReachableWithoutASession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/login");
        Assertions.assertTrue(allowed(request), "the login form itself must never be gated");
    }

    @Test
    public void everyAdminPageRequiresASession() throws Exception {
        for (String path : new String[]{
                "/admin", "/admin/prompts", "/admin/settings", "/admin/documents"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            Assertions.assertFalse(allowed(request),
                    path + " must be gated — an open admin page exposes every prompt");
        }
    }

    @Test
    public void everyAdminApiRequiresASession() throws Exception {
        for (String path : new String[]{
                "/admin/api/prompts", "/admin/api/settings", "/admin/api/suggestions",
                "/admin/api/provider", "/admin/api/config.xml"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            Assertions.assertFalse(allowed(request), path + " must be gated");
        }
    }

    @Test
    public void grantedSessionReachesTheAdminPages() throws Exception {
        for (String path : new String[]{
                "/admin", "/admin/prompts", "/admin/settings", "/admin/documents"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setSession(grantedSession());
            Assertions.assertTrue(allowed(request), path + " must open for a granted session");
        }
    }

    @Test
    public void chatPageIsNeverGated() throws Exception {
        // The chat page is the public entry point; the gate must not touch it.
        for (String path : new String[]{"/", "/chat", "/api/health", "/app-config.xml"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            Assertions.assertTrue(allowed(request), path + " must stay public");
        }
    }

    @Test
    public void ungrantedPageRequestRedirectsToLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/prompts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        Assertions.assertFalse(result);
        Assertions.assertEquals(302, response.getStatus());
        Assertions.assertTrue(response.getRedirectedUrl().endsWith("/admin/login"),
                "expected a redirect to the login page, got: " + response.getRedirectedUrl());
    }

    @Test
    public void ungrantedApiCallGets401NotALoginRedirect() throws Exception {
        // An XHR must not receive login HTML where it expects JSON.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/api/prompts");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        Assertions.assertFalse(result);
        Assertions.assertEquals(401, response.getStatus());
    }

    // ---- Key format ----

    @Test
    public void todayKeyIsEightDigits() {
        Assertions.assertTrue(HomeController.todayKey().matches("\\d{8}"),
                "the admin key is today's date as yyyyMMdd");
    }

    @Test
    public void onlyTodaysDateIsAValidKey() {
        Assertions.assertTrue(HomeController.isValidKey(HomeController.todayKey()));
        Assertions.assertTrue(HomeController.isValidKey("  " + HomeController.todayKey() + "  "),
                "surrounding whitespace is trimmed");
    }

    @Test
    public void gateIgnoresTheContextPath() throws Exception {
        // Deployed at /fshipbotx, getRequestURI() is prefixed while the mapped
        // path is not. Matching on the raw URI would miss the /admin prefix and
        // wave every request through.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/fshipbotx/admin/prompts");
        request.setContextPath("/fshipbotx");

        Assertions.assertEquals("/admin/prompts", PageAccessInterceptor.pathWithinApp(request));
        Assertions.assertFalse(allowed(request), "the context path must be stripped before matching");
    }

    @Test
    public void trailingSlashDoesNotBypassTheGate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/admin/settings/");

        Assertions.assertFalse(allowed(request), "/admin/settings/ must not dodge the gate");
    }

    @Test
    public void wrongOrMalformedKeysAreRejected() {
        Assertions.assertFalse(HomeController.isValidKey(null));
        Assertions.assertFalse(HomeController.isValidKey(""));
        Assertions.assertFalse(HomeController.isValidKey("1234567"), "too short");
        Assertions.assertFalse(HomeController.isValidKey("20240101"), "a valid date, but not today");
        Assertions.assertFalse(HomeController.isValidKey("abcdefgh"));
    }
}
