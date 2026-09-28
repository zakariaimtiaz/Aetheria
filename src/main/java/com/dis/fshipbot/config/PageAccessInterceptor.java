package com.dis.fshipbot.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Session gate for the whole admin panel.
 *
 * <p>The key is today's date as {@code yyyyMMdd} (validated by
 * {@code POST /api/validate-key}), so there is no separate admin account. The
 * grant lives in the HTTP session under {@link #SESSION_KEY}.
 *
 * <p>Only the login page and the key-validation endpoint are open; every other
 * {@code /admin/**} page and API redirects to the login screen. Matching paths
 * are registered in {@code WebMvcConfig}.
 */
@Component
public class PageAccessInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "pageAccessGranted";

    /** Paths reachable without a grant: the login form itself. */
    private static final String[] PUBLIC_PATHS = {"/admin/login"};

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = pathWithinApp(request);

        for (String open : PUBLIC_PATHS) {
            if (open.equals(path)) {
                return true;
            }
        }

        if (!path.startsWith("/admin")) {
            return true;
        }

        if (!isGranted(request.getSession(false))) {
            // XHR: answer 401 so the page can show a message instead of rendering
            // the login HTML inside a JSON parse.
            if (isApiCall(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            response.sendRedirect(request.getContextPath() + "/admin/login");
            return false;
        }
        return true;
    }

    /**
     * Path relative to the app root, e.g. {@code /admin/prompts}.
     *
     * <p>Derived from the request URI rather than {@code getServletPath()}: with
     * the DispatcherServlet mapped at {@code /} the two agree, but servletPath
     * is empty whenever the container has not resolved a servlet for the request,
     * which would silently open the gate. Failing closed is the only safe
     * default here.
     */
    public static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty()) {
            return "/";
        }
        // Strip a trailing slash so /admin/settings/ and /admin/settings match.
        return uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private boolean isGranted(HttpSession session) {
        return session != null && session.getAttribute(SESSION_KEY) != null;
    }

    private boolean isApiCall(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String requestedWith = request.getHeader("X-Requested-With");
        return "XMLHttpRequest".equalsIgnoreCase(requestedWith)
                || (accept != null && accept.contains("application/json"));
    }
}
