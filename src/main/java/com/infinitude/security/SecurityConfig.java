package com.infinitude.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security filter chain (§10, §17.2, §17.6, §17.7 PROJECT_ARCHITECTURE.md).
 *
 * <p><b>CSRF:</b> Spring Security's default (synchronizer-token) CSRF protection is disabled
 * here. This is intentional and matches §17.6's decision, not an oversight:</p>
 * <ul>
 *   <li>The auth cookie is set with {@code SameSite=Strict}, which already stops the browser
 *       from attaching it on cross-site requests (the primary mitigation per §17.6).</li>
 *   <li>CORS (below) is restricted to the configured frontend origin(s) only, so state-changing
 *       requests from arbitrary origins are rejected before they'd ever reach a CSRF check.</li>
 *   <li>Spring Security's classic CSRF token mechanism is designed around server-rendered forms
 *       and session cookies with looser SameSite defaults; combining it with a REST JSON API and
 *       an httpOnly SameSite=Strict cookie would add complexity without meaningfully increasing
 *       protection at this stage. §17.6 explicitly earmarks a double-submit/custom-header scheme
 *       as a follow-up "once note-mutating endpoints exist (Phase 2+)" - that is a future
 *       enhancement, not something to bolt on ad hoc onto the auth-only surface that exists today.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final List<String> allowedOrigins;

    public SecurityConfig(TokenService tokenService,
                           RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                           @Value("${infinitude.cors.allowed-origins}") String allowedOriginsProperty) {
        this.jwtAuthenticationFilter = new JwtAuthenticationFilter(tokenService);
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.allowedOrigins = List.of(allowedOriginsProperty.split(","));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // See class-level javadoc for why CSRF is disabled given the SameSite=Strict
                // cookie + restricted-CORS defense-in-depth already in place.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // Public per §17.3: the routes themselves aren't gated; /me still 401s
                        // internally without a valid cookie via AuthController's own check.
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Everything else requires a valid JWT cookie. Nothing else exists yet
                        // (Phases 2-9 are not built), but the rule must be in place now so future
                        // Notes endpoints are secure by default rather than by afterthought.
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With"));
        // Required so the browser sends/receives the httpOnly auth cookie cross-port (frontend
        // dev server vs backend) - CORS is intentionally restricted to known origins above so
        // this doesn't open the door to arbitrary sites (§11 item 8, §17.6).
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
