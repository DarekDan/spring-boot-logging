package pl.piomin.logging.filter;

import com.github.loki4j.slf4j.marker.LabelMarker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pl.piomin.logging.util.UniqueIDGenerator;
import pl.piomin.logging.wrapper.SpringRequestWrapper;
import pl.piomin.logging.wrapper.SpringResponseWrapper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class SpringLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringLoggingFilter.class);
    private UniqueIDGenerator generator;
    private String ignorePatterns;
    private boolean logHeaders;

    @Autowired
    ApplicationContext context;

    public SpringLoggingFilter(UniqueIDGenerator generator, String ignorePatterns, boolean logHeaders) {
        this.generator = generator;
        this.ignorePatterns = ignorePatterns;
        this.logHeaders = logHeaders;
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (ignorePatterns != null && request.getRequestURI().matches(ignorePatterns)) {
            chain.doFilter(request, response);
        } else {
            generator.generateAndSetMDC(request);
            try {
                getHandlerMethod(request);
            } catch (Exception e) {
                LOGGER.trace("Cannot get handler method");
            }
            final long startTime = System.currentTimeMillis();
            final SpringRequestWrapper wrappedRequest = new SpringRequestWrapper(request);
            LabelMarker marker = LabelMarker.of(() -> Map.of("audit", "true",
                    "X-Request-ID", MDC.get("X-Request-ID"),
                    "X-Correlation-ID", MDC.get("X-Correlation-ID")));
            if (logHeaders)
                LOGGER.info(marker, "Request: method={}, uri={}, payload={}, headers={}, audit={}", wrappedRequest.getMethod(),
                        wrappedRequest.getRequestURI(), IOUtils.toString(wrappedRequest.getInputStream(),
                        wrappedRequest.getCharacterEncoding()), wrappedRequest.getAllHeaders(), true);
            else
                LOGGER.info(marker, "Request: method={}, uri={}, payload={}, audit={}", wrappedRequest.getMethod(),
                        wrappedRequest.getRequestURI(), IOUtils.toString(wrappedRequest.getInputStream(),
                        wrappedRequest.getCharacterEncoding()), true);
            final SpringResponseWrapper wrappedResponse = new SpringResponseWrapper(response);
            wrappedResponse.setHeader("X-Request-ID", MDC.get("X-Request-ID"));
            wrappedResponse.setHeader("X-Correlation-ID", MDC.get("X-Correlation-ID"));

            try {
                chain.doFilter(wrappedRequest, wrappedResponse);
            } catch (Exception e) {
                logResponse(startTime, wrappedResponse, 500);
                throw e;
            }
            logResponse(startTime, wrappedResponse, wrappedResponse.getStatus());
        }
    }

    private void logResponse(long startTime, SpringResponseWrapper wrappedResponse, int overriddenStatus) throws IOException {
        final long duration = System.currentTimeMillis() - startTime;
        wrappedResponse.setCharacterEncoding("UTF-8");
        LabelMarker marker = LabelMarker.of(() -> Map.of("X-Response-Time", String.valueOf(duration),
                "X-Response-Status", String.valueOf(overriddenStatus),
                "audit", "true",
                "X-Request-ID", MDC.get("X-Request-ID"),
                "X-Correlation-ID", MDC.get("X-Correlation-ID")));
        if (logHeaders)
            LOGGER.info(marker, "Response({} ms): status={}, payload={}, headers={}, audit={}", duration,
                    overriddenStatus, IOUtils.toString(wrappedResponse.getContentAsByteArray(),
                            wrappedResponse.getCharacterEncoding()), wrappedResponse.getAllHeaders(), true);
        else
            LOGGER.info(marker, "Response({} ms): status={}, payload={}, audit={}", duration,
                    overriddenStatus,
                    IOUtils.toString(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding()), true);
    }

    private void getHandlerMethod(HttpServletRequest request) throws Exception {
        RequestMappingHandlerMapping mappings1 = (RequestMappingHandlerMapping) context.getBean("requestMappingHandlerMapping");
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = mappings1.getHandlerMethods();
        HandlerExecutionChain handler = mappings1.getHandler(request);
        if (Objects.nonNull(handler)) {
            HandlerMethod handler1 = (HandlerMethod) handler.getHandler();
            MDC.put("X-Operation-Name", handler1.getBeanType().getSimpleName() + "." + handler1.getMethod().getName());
        }
    }

}
