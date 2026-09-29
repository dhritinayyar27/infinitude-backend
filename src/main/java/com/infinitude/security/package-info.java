/**
 * Spring Security / JWT configuration and filters implementing Email + OTP authentication
 * (§17 PROJECT_ARCHITECTURE.md, pulled forward from the original Phase 10 slot - see §13).
 * Contains {@code AuthController}, {@code AuthService}, {@code OtpService}, {@code TokenService},
 * {@code SecurityConfig}, {@code JwtAuthenticationFilter} and supporting types. Every note will
 * carry a {@code userId} from day one so ownership checks can be added to future Notes endpoints
 * without a data migration.
 */
package com.infinitude.security;
