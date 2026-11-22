package org.dromara.common.web.filter;

import org.dromara.common.core.utils.RequestBodyLimits;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Repeatable 过滤器
 *
 * @author ruoyi
 */
public class RepeatableFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        ServletRequest requestWrapper = null;
        if (request instanceof HttpServletRequest
            && RequestBodyLimits.isJsonContentType(request.getContentType())) {
            try {
                requestWrapper = new RepeatedlyRequestWrapper((HttpServletRequest) request, response);
            } catch (RequestBodyLimits.BodyTooLargeException e) {
                ((jakarta.servlet.http.HttpServletResponse) response).sendError(
                    jakarta.servlet.http.HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
        }
        if (null == requestWrapper) {
            chain.doFilter(request, response);
        } else {
            chain.doFilter(requestWrapper, response);
        }
    }

    @Override
    public void destroy() {

    }
}
