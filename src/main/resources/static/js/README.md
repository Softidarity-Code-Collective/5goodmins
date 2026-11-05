# Vendored JavaScript

## htmx 2.0.11

| | |
|---|---|
| File | `htmx-2.0.11.min.js` |
| Upstream | https://unpkg.com/htmx.org@2.0.11/dist/htmx.min.js |
| SHA-256 | `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717` |
| SRI (sha384) | `sha384-2OatzQy1H+Zd/IIrjr1TcuDGqLXeHhbooAyJY1KdQMKnr4LZ22k31GBLdYKHmVjg` |
| Added | 2026-10-29 |
| Licence | BSD Zero Clause (0BSD) |

Downloaded from unpkg and checked byte-for-byte against the same version on
jsDelivr (`https://cdn.jsdelivr.net/npm/htmx.org@2.0.11/dist/htmx.min.js`);
both produce the SHA-256 above. Two independent mirrors agreeing is not a
signature, but it is the difference between "I downloaded a file" and "I can
tell you what is in it".

Verify the committed copy at any time:

```bash
sha256sum src/main/resources/static/js/htmx-2.0.11.min.js
```

**Why vendored rather than a CDN** — ADR-020. The Content-Security-Policy is
`default-src 'self'`, so a CDN would have to be named in the policy as an
origin allowed to run scripts on this site; a third-party request also reveals
every visitor's IP to that third party; and the application should run with no
network.

**Upgrading:** download the new version, record it here with its hash, commit
it under a filename carrying the version, and change the one `<script>` tag in
`templates/layout.html`. The version is in the filename so a cached old copy
can never be served as a new one.
