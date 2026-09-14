package com.ttegeoji.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 내부 API(/internal/v1/**) 서비스 토큰 인증(10 §4.7).
 * Authorization: Bearer <SERVICE_AUTH_TOKEN> 을 timing-safe 로 비교한다. 설정값이 비면 전부 401(열어 두지 않는다).
 * SecurityConfig 의 내부 체인에만 넣는다. @Component 로 두면 서블릿 필터로 전 경로에 붙으므로 두지 않는다.
 * 토큰은 헤더 값이든 설정값이든 로그에 남기지 않는다.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    static final String UNAUTHORIZED_BODY = "{\"code\":\"UNAUTHORIZED\"}";
    private static final String BEARER_PREFIX = "Bearer ";

    private final GeojiProperties properties;
    private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public ServiceTokenFilter(GeojiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            writeUnauthorized(response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "service", null, AuthorityUtils.createAuthorityList("ROLE_SERVICE")));
        SecurityContextHolder.setContext(context);
        // 요청 속성에도 저장한다. 5xx·404 의 /error 디스패치는 공개 체인을 타는데, 거기서 인증이 안 보이면
        // 401 로 바뀌어 워커가 5xx 재전송(10 §4.7)을 하지 못한다.
        contextRepository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }

    private boolean isAuthorized(String header) {
        String expected = properties.internal().serviceToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String presented = header.substring(BEARER_PREFIX.length());
        // 길이가 달라도 걸리는 시간이 같도록 해시끼리 비교한다
        return MessageDigest.isEqual(sha256(presented), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
