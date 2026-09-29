/**
 * OTP email delivery abstraction (§17.2 PROJECT_ARCHITECTURE.md): {@code EmailService} interface
 * + {@code EmailServiceImpl} (SMTP via spring-boot-starter-mail, with a clearly-guarded dev-only
 * fallback when SMTP isn't configured). Never involved with Gemini in any way.
 */
package com.infinitude.email;
