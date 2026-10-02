# Pending Backend Changes

Snapshot: 2026-10-02. Covers the current worktree relative to `HEAD`, including work present before the TOC-only change. No commits are ahead of the locally tracked `origin/main`. This is not a fresh remote check; no fetch, commit, or push was performed.

## Retained Pending Work

- Added owned TOC record CRUD with `Note`, `Section`, status models, `NotesRepository`, DTOs, mapper, service, and `/api/notes` controller. All record access checks authenticated ownership.
- Added Gemini REST request/response models, JSON TOC parsing, difficulty-specific prompts, and the REST client configuration.
- Added TOC generation, retrieval, editing, ordering, and persistence under `/api/notes/{noteId}/toc` and `/toc/generate`.
- Added TOC ownership/not-found and AI error responses to centralized exception handling.
- Added `dotenv-java` and Spring-native loading of a local backend `.env`; existing OS variables/system properties take precedence. Added ignored `.env` files and a safe environment example.
- Kept the existing CORS additions for `PATCH` and `X-Requested-With`.

## Email-Only OTP And Environment Follow-Up

- Removed development-mode OTP logging and the `EMAIL_DEV_MODE` switch. Signup and login codes are sent by email only in every environment.
- Missing mail sender, SMTP host, or sender address prevents application startup. SMTP failures still preserve generic auth responses for anti-enumeration protection, but never log the OTP, recipient, or raw SMTP exception message.
- Added configurable `MAIL_SMTP_AUTH` and `MAIL_STARTTLS_ENABLE`, plus bounded SMTP connection/read/write timeouts.
- Replaced main-method mutation of JVM system properties with a registered Spring environment postprocessor. This also works in Spring tests and initializes before config-data processing.
- Backend `.env` is selected from the workspace root or backend launch directory; a missing file is allowed for OS-configured deployments. Explicit Spring configuration and OS/JVM values override `.env` defaults.
- Added mail delivery/log-safety tests and dotenv parsing, precedence, path-selection, registration, and real Spring startup tests. The context smoke test uses a mocked mail sender, not live SMTP.
- The local backend `.env` was checked by variable name only: mail settings were not actively declared. No secret values were displayed or altered.

## TOC-Only Changes

- Removed full-note generation, async orchestration, section regeneration/content-edit endpoints, Markdown assembly, and unused CommonMark dependencies.
- Removed user API-key controllers, persistence/service/DTOs, encryption, rotation/cooldowns, and their exception handlers.
- Removed user model settings endpoints/service/DTOs and the obsolete user model preference.
- TOC generation now uses a backend-owned key and model, without querying user settings. A missing key leaves the existing draft unchanged and returns a configuration error.
- Gemini receives the key in `x-goog-api-key`, not a URL query parameter. HTTP/network errors do not return raw transport messages that could expose credentials.
- Default model is `gemini-2.5-flash`; 503 responses can fall back to server-defined `gemini-2.5-flash` / `gemini-2.5-flash-lite`. Other failures are not masked by model fallback.
- Removed generation/storage/encryption configuration. Creation accepts an omitted content style, retaining a legacy default for stored-record compatibility.

## Configuration And Compatibility

- Set `GEMINI_API_KEY` in the backend process environment or local backend `.env`. Optional `GEMINI_MODEL` overrides the default. Never use a `VITE_` variable for this secret.
- Existing MongoDB, JWT, CORS, and OTP variables remain. Set `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, and `MAIL_FROM` for your SMTP provider; `.env.example` lists all supported mail settings. `EMAIL_DEV_MODE` is no longer used.
- Launch from either the workspace root or backend directory to load the backend `.env`. For a packaged JAR, place `.env` in its working directory. Restart the backend after changing environment variables.
- Existing MongoDB records and legacy content/status fields were not deleted or migrated. Old user key documents are unused; this change does not purge their collection.
- Removed routes: `/api/settings/**`, note `/generate`, `/status`, and section content/regeneration operations. Retained `/api/notes` naming avoids unnecessary record/API renames.
- The shared server key's quota is shared across users; per-user key rotation is no longer available.

## Verification

- A clean backend compile and focused TOC tests passed.
- Five regression tests cover server-key selection, missing-key behavior, AI failure state, saved section IDs, and header-based Gemini authentication.
- Ten additional focused tests cover email-only OTP behavior and Spring `.env` integration; all 15 focused tests passed.
- Full backend suite passed: 16 tests, zero failures/errors, including the application context smoke test with mocked mail.
- Command: `./mvnw.cmd test` from this directory.
- No real Gemini or SMTP calls were made. Live mail delivery requires provider credentials; authenticated database/SMTP integration was not verified by these focused tests.