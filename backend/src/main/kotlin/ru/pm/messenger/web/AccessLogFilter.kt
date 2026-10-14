package ru.pm.messenger.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AccessLogFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val started = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val millis = (System.nanoTime() - started) / 1_000_000
            log.info(
                "http method={} path={} status={} durationMs={}",
                request.method,
                request.requestURI,
                response.status,
                millis,
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AccessLogFilter::class.java)
    }
}
