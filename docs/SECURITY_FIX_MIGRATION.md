# Security hardening: deployment notes

These changes tighten server authorization and token validation. They do not complete the wider architecture redesign or add per-device access control, enrollment, revocation, durable storage, acknowledged delivery, subscription ownership, or audio-session isolation.

## Render configuration

Before deploying, configure JWT_SECRET explicitly with a strong random signing secret containing at least 32 characters. Startup rejects missing, short, and default secrets. Do not commit or log this value. The token generator accepts JWT_SECRET from the environment and no longer prints the signing secret.

JWTs must contain an expiration and issuer project-sentinel. Administrator identities must use the signed ADMIN- namespace. Host identities do not gain administrator privileges before registration. Existing tokens lacking the required claims must be reissued. If the previously embedded host credential was distributed, revoke it through signing-key rotation; this also invalidates all other tokens signed with that key.

The host no longer embeds a token. Configure the server URL and device token through the existing session/login mechanism before using it. An APK rebuild is required to remove the credential from installed copies; repository changes cannot erase old binaries or Git history.

## Enforced boundaries

- Fleet REST endpoints and metrics require an administrator identity.
- Command, audio-control, and file-control WebSocket requests require an administrator identity.
- Host data/results require a registered host session.
- Binary host data rejects administrators and unregistered sessions.
- A connection cannot authenticate again to change its principal.
- Authenticated principal and registered device identity are stored separately.

HTTP timeouts and a 2 MiB inbound WebSocket message limit bound some resource-exhaustion risks. WebSocket writes have a five-second deadline. These limits do not replace rate limits, bounded command execution, or connection quotas.



This is a local code change. No Render configuration, credential, deployment, or installed APK has been changed automatically.
