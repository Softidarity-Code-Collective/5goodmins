# Fonts

Self-hosted font files go here. They are served from `/fonts/**`, which
`SecurityConfig` permits anonymously alongside `/css`, `/js` and `/images`.

Two requirements, both from section 1 of the frontend brief:

1. **Cyrillic coverage.** Most display and decorative typefaces have none, and
   a browser falls back silently — the Latin text in a mockup looks right and
   the Bulgarian text, which is all the real content, does not. Check the
   character set before choosing anything.
2. **Self-hosted, not hotlinked.** A font loaded from a third-party host is a
   third-party request on every page load, and the Content-Security-Policy
   (ADR-020) is `default-src 'self'` — a remote font will not load at all.

Subset the files if they are large: Cyrillic plus Latin is usually a fraction
of a full face, and the font is on the critical path for the first paint.
