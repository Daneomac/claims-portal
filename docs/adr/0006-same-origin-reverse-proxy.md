# 0006 — Serve the SPA and proxy the API from one origin

**Status:** Accepted

## Context
The React app needs to call claims-api. If the browser calls the API on a different origin,
the API needs CORS configuration, and there are two public entry points to secure.

## Decision
In containers, nginx serves the built SPA and reverse-proxies `/api/*` to claims-api. In
development, Vite's dev-server proxy does the same. The frontend always uses relative `/api`
URLs.

## Alternatives considered
- **Separate origins + CORS:** works, but adds configuration that's easy to get wrong
  (wildcards) and has no benefit here.
- **Serve the SPA from Spring Boot's static resources:** one fewer container, but couples the
  frontend build and release cycle to the API.

## Consequences
- No CORS configuration at all; the frontend needs no environment-specific API URL.
- One place for security headers (CSP etc.), request-size limits and per-IP rate limiting.
- Actuator endpoints aren't routed through nginx, so they're not publicly reachable.
- Mirrors a typical production setup: an ingress or gateway in front of static hosting and
  APIs.
