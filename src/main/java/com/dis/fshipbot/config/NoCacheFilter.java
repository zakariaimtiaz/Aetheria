package com.dis.fshipbot.config;
import javax.servlet.*;

import javax.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class NoCacheFilter implements Filter {

    // Explicit lifecycle methods: javax.servlet.Filter declares init/destroy abstract
    // on Servlet 3.1 (Tomcat 8.5) — they became default methods only in Servlet 4.0
    // (Tomcat 9). Compiling against Tomcat 9 hides the gap; Tomcat 8.5 dies with
    // AbstractMethodError at filter startup without these.
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // stateless header filter — nothing to initialize
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        httpResponse.setHeader("Pragma", "no-cache");
        httpResponse.setDateHeader("Expires", 0);
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // stateless header filter — nothing to clean up
    }
}
