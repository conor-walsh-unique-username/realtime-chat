package com.example.chat.auth;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder
    ) {
        // Login loads the account through UserDetailsService and checks its encoded password.
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, DaoAuthenticationProvider provider, ObjectMapper objectMapper
    ) throws Exception {
        // These filters run before controllers. CSRF checks still apply to public POSTs.
        // A CSRF token helps stop another site from using the browser's login cookie to write data.
        http
                .authenticationProvider(provider)
                .cors(Customizer.withDefaults())
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                // Keep login in a server session and replace its ID after signing in.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/api/health", "/actuator/health", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login").permitAll()
                        .anyRequest().authenticated()
                )
                // The frontend needs JSON errors instead of redirects to an HTML login page.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                                "AUTH_REQUIRED", "Sign in to use this endpoint."
                        ))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                objectMapper, request, response, HttpStatus.FORBIDDEN,
                                exception instanceof CsrfException ? "INVALID_CSRF" : "FORBIDDEN",
                                exception instanceof CsrfException
                                        ? "Fetch a CSRF token and send it with this request."
                                        : "You do not have permission to perform this action."
                        ))
                )
                // Spring consumes form-encoded username/password here; there is no login controller.
                .formLogin(login -> login
                        .loginPage("/api/auth/login")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .failureHandler((request, response, exception) -> writeError(
                                objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                                "BAD_CREDENTIALS", "Incorrect username or password."
                        ))
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("CHATSESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                );

        return http.build();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        var cors = new CorsConfiguration();
        // The frontend uses another port, so allow that exact origin to send session cookies.
        cors.setAllowedOrigins(List.of("http://localhost:3000"));
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static void writeError(
            ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String code, String detail
    ) throws IOException {
        // Filter errors happen before controller advice can handle them, so write their JSON here.
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "type", "about:blank",
                "status", status.value(),
                "title", status.getReasonPhrase(),
                "detail", detail,
                "code", code,
                "instance", request.getRequestURI()
        ));
    }
}
