# Infinitude backend

Spring Boot backend using Java 21, Maven, MongoDB, and the Gemini REST API.

## Deploy on Render with Docker

1. Push this backend repository, including [Dockerfile](Dockerfile) and
   [.dockerignore](.dockerignore), to GitHub.
2. In Render, choose **New > Web Service** and connect `infinitude-backend`.
3. Set **Language** to **Docker**. For this standalone backend repository:
   - **Root Directory:** leave blank.
   - **Dockerfile Path:** `./Dockerfile`.
   - **Docker Build Context Directory:** `.` (the repository root).
   - **Docker Command:** leave blank; the image already starts the application.
4. Configure the environment variables below in Render before deploying.
5. Deploy. Render builds the executable JAR and runs it using Java 21 as a
   non-root user. The Docker entrypoint passes Render's `PORT` as an explicit
   server argument, falling back to `SERVER_PORT`, then `8080` for local use.
   Outside Docker, Spring's direct `SERVER_PORT` override still takes precedence.

If deploying from a monorepo instead, set **Root Directory** to `Backend` and
keep the Dockerfile and build context relative to that directory.

### Environment variables

| Variable | Value |
| --- | --- |
| `MONGODB_URI` | Your hosted MongoDB connection string, including the database name (for example, MongoDB Atlas). Do not use `localhost`. |
| `JWT_SECRET` | A new high-entropy signing secret; generate one with `openssl rand -base64 48`. Never use the development default or example placeholder. |
| `FRONTEND_ORIGIN` | Exact HTTPS frontend origin, without a trailing slash. |
| `GEMINI_API_KEY` | Your server-side Gemini API key (or use `GEMINI_API_KEYS` for a key pool). |
| `MAIL_HOST` | Your SMTP provider's hostname. Required only for direct SMTP mode. |
| `MAIL_PORT` | Your SMTP provider's submission port; defaults to `587`. |
| `MAIL_USERNAME` | SMTP username. |
| `MAIL_PASSWORD` | SMTP password or app password. |
| `MAIL_FROM` | Verified sender email address. Required for application startup. |
| `EMAIL_DELIVERY` | `smtp` (default) or `vercel` for server-to-server delivery through the frontend's Vercel function. |
| `EMAIL_RELAY_URL` | Required in `vercel` mode: `https://YOUR-FRONTEND-DOMAIN/api/send-otp`. |
| `EMAIL_RELAY_SECRET` | Required in `vercel` mode: shared random secret of at least 32 bytes, identical to Vercel's server-only value. |

Other optional settings and defaults are listed in [.env.example](.env.example).
`MONGODB_URI` is required; there is no hardcoded localhost fallback. Local
development reads it from the backend `.env`. On Render, set `MONGODB_URI` in
the service's environment settings and redeploy after changing it. Render's
environment value takes precedence over a local `.env`. The backend uses
Spring Boot 4's `spring.mongodb.uri` property to configure the connection.
Keep secrets in Render's environment settings, never in the Dockerfile, Git, or
frontend variables. The Docker build uses an allowlisted context: local `.env`
files, Git metadata, tests, and prebuilt `target` files are not included.

### Deployment considerations

- Allow the Render service's outbound IP addresses in your hosted MongoDB
  network configuration.
- Render Free web services block outbound SMTP ports `25`, `465`, and `587`.
  Use `EMAIL_DELIVERY=vercel` to send signed HTTPS requests to the Vercel function,
  which sends via Gmail SMTP. Alternatively, use paid hosting or an SMTP provider
  with a supported alternative port. Docker does not bypass this restriction.
- Authentication uses a `Secure`, `SameSite=Strict` cookie. Serve the frontend
  and API over HTTPS on the same site (for example, `app.example.com` and
  `api.example.com`, or through a same-origin proxy). Unrelated frontend/API
  domains will prevent browser cookie authentication even if CORS is configured.
- Leave Render's HTTP health check path unset to use its default TCP check;
  this application does not currently expose a public health endpoint.
- Render's filesystem is ephemeral. Keep persistent application data in MongoDB.
- The JVM heap is capped at 65% of container memory, leaving room for native
  memory and threads. Adjust the service memory plan if generation workloads
  exhaust available memory.
- Image packaging skips tests; run the Maven tests before deploying.

See Render's [Docker deployment guide](https://render.com/docs/docker) and
[Free instance limitations](https://render.com/docs/free).

## Gmail delivery through Vercel (Render Free)

First deploy and configure the frontend's `/api/send-otp` function with Gmail app
password credentials and Upstash Redis replay protection (see the frontend README).
Then set the following in Render and redeploy:

```text
EMAIL_DELIVERY=vercel
EMAIL_RELAY_URL=https://YOUR-FRONTEND-DOMAIN/api/send-otp
EMAIL_RELAY_SECRET=<same random secret configured in Vercel>
MAIL_FROM=<same Gmail address configured as GMAIL_USER in Vercel>
```

`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, and `MAIL_PASSWORD` are not needed in
this mode. Gmail credentials belong only in Vercel's server environment.
OTP generation, hashing, persistence, expiry, cooldowns, verification and generic
authentication responses remain in Spring. Neither the generated OTP nor the
relay secret is sent to the browser. Templates and inline images remain backend-owned.
Relay calls use a 25-second timeout and do not retry automatically, to avoid
duplicate messages after ambiguous delivery failures. A failed send is logged
without sensitive details; the existing generic auth response is unchanged.
Users can request a fresh OTP after the existing resend cooldown.

## Build and run locally with Docker

From the backend repository directory:

```powershell
docker build -t infinitude-backend .
docker run --rm --name infinitude-backend -p 8080:8080 --env-file .env infinitude-backend
```

Copy [.env.example](.env.example) to `.env` and supply real values first. Use a
hosted MongoDB URI, or `host.docker.internal` instead of `localhost` when MongoDB
is running on the Windows host. The env file is passed at runtime, not baked into
the image.
