# Frontend Brief — 5 Good Minutes (5goodmins.com)

For the frontend contributor. Read alongside `ARCHITECTURE.md`; where the two
disagree, `ARCHITECTURE.md` wins and this file gets corrected. Last reconciled
against ARCHITECTURE.md Rev 19 (2026-10-28): **pages 6, 7 and 8 now have
working backends and placeholder front ends.** The pass before that was Rev 16
(2026-10-19), which took the email count from three to four and gave page 8 the
recovery copy that the absence of a resend form depends on.

**Pages 6, 7 and 8 are yours to design — the templates there are not a
design.** They are unstyled structural placeholders, written so the controllers
have something to render and so the behaviour could be tested: no stylesheet,
no class attributes, no layout, no font choice, no fragments. Section 5's field
fragment and everything in section 7 are still open, and the UI copy on pages 6
and 7 is placeholder editorial you should rewrite. Delete and rebuild the markup
freely.

What must survive, because the backend depends on it, is listed in a comment at
the top of each of those four template files. In summary: forms use `th:action`
rather than `action`, or the CSRF input is absent and every submission is a
silent 403; the `th:field` bindings and their `th:errors` stay paired; the
consent checkbox stays inside its label, because Thymeleaf renders a bound
checkbox as `id="privacyAccepted1"` and a hand-written `for=` dangles; every
string stays in the bundles; the two registration outcomes keep rendering the
identical page; and page 8 keeps saying, out in the open rather than behind a
disclosure, how to recover from an email that never arrived.

**In one line:** a Bulgarian-language directory of small activism actions —
petitions, emails to officials, surveys — that users browse by category and
region, subscribe to, and receive as a daily email digest. Every action links
out to an external site. We host no petitions ourselves.

**Size of the job:** ~20 page templates, ~15 reusable fragments, 4 email
templates for the MVP — two of which exist already, as working HTML you are
free to redesign (see section 4).

---

## 1. Technical ground rules

These are not preferences; the backend architecture depends on them.

**Server-rendered Thymeleaf templates, not a separate frontend app.** HTML is
produced by Spring Boot. There is no React/Vue, no npm build step, no
client-side router, no API-driven rendering. Pages are `.html` files with
Thymeleaf attributes (`th:text`, `th:each`, `th:if`, `th:fragment`), living in
`src/main/resources/templates/`. Static assets go in
`src/main/resources/static/`.

**htmx for partial updates only.** Filtering the action list, pagination, and
form submissions that shouldn't reload the whole page use htmx attributes
(`hx-get`, `hx-post`, `hx-target`, `hx-swap`) against endpoints that return an
HTML fragment. Everything else is a normal link or form post.

**Vanilla JS only, and as little as possible.** A mobile nav toggle, a
character counter, a language switcher. No framework, no bundler. If something
seems to need more than ~50 lines of JS, raise it before building it.

**No hardcoded text, anywhere.** Every visible string comes from
`messages_bg.properties` / `messages_en.properties` via `th:text="#{key}"`.
Bulgarian is the default; English must be selectable. This includes button
labels, placeholders, validation messages, empty states, `aria-label`s, page
titles and email subjects. Retrofitting this later is miserable, so it starts
with the first template.

**Fonts must cover Cyrillic.** Most display and decorative typefaces don't, and
the browser silently falls back to something ugly for Bulgarian text. Check
Cyrillic coverage before choosing anything. Self-host the font files rather
than hotlinking, so there's no third-party dependency at page load.

**CSRF.** Spring Security blocks POSTs without a token. The token goes in a
`<meta>` tag in the base layout, and `<body>` carries `hx-headers` so every
htmx POST sends it. Plain forms get a hidden input. Without this everything
silently returns 403.

**Content is Bulgarian, code is English.** Category names, regions and statuses
are English constants in the code (`HUMAN_RIGHTS`, `STARA_ZAGORA`) and are
translated through the properties files. Never display the constant.

**Mobile first.** Most traffic will arrive from a phone, via a shared link or a
digest email.

---

## 2. What we agreed on "PWA"

- **Responsive, mobile-first layout** — yes, and it's the highest-value part.
- **Web app manifest + icons** (installable to home screen) — yes, cheap, do it.
- **Service worker / offline caching** — **no.** Every action links to an
  external site that needs a connection, and caching pages that carry session
  state and CSRF tokens causes stale-token 403s and stale pages after deploys.
- **Web push** — v2.

---

## 3. Domain vocabulary

**Action** — one activism item. Has a title, description, exactly one *type*,
one to three *categories*, one to three *regions*, an **optional** external
link, a mandatory end date, an optional image, an optional video link, and an
author.

The external link is optional, so **every design that shows the outbound
call-to-action needs a variant without one**. Most actions link out; some are
valid as a description alone.

**Types (5):** `PETITION`, `LETTER_OR_EMAIL`, `SURVEY`, `SOCIAL_MEDIA`, `OTHER`

**Categories (10):** `NATURE`, `ANIMALS`, `CHILDREN`, `HEALTHCARE`,
`DISABILITY`, `LOCAL_COMMUNITIES`, `SOCIAL`, `HUMAN_RIGHTS`, `LABOUR_RIGHTS`,
`POLITICAL`

**Regions (30):** Bulgaria's 28 oblasti, plus `NATIONAL` and `INTERNATIONAL`.
An action can be e.g. `NATIONAL` + `BURGAS` at once.

**Statuses (4):** `PENDING` (awaiting admin approval), `PUBLISHED`, `REJECTED`
(refused *or* taken down by an admin), `WITHDRAWN` (the poster's own reversible
choice). Only `PUBLISHED` actions are publicly visible.

There is no `HIDDEN`. It was removed: once an admin can move an action from
`PUBLISHED` back to `REJECTED`, `REJECTED` *is* the takedown state, and two
names for one fact is how states drift apart. An admin moves an action between
the first three in any direction; only the poster uses `WITHDRAWN`.

**Roles:** `USER` (can submit; submissions need approval), `POSTER` (approved
contributor; submissions publish immediately), `ADMIN`.

**Expiry** — an action past its end date is not shown in listings.

Field limits, for character counters and layout: title 5–200 chars,
description up to 5000, display name up to 80, bio up to 1000, website URL up
to 500, external link up to 2048. Title and display name also reject
whitespace-only input, so a counter showing 5 characters of spaces is not a
valid title.

---

## 4. Page inventory — MVP

### Public

| # | Page | Notes |
|---|---|---|
| 1 | **Action list / home** | *Placeholder built, everything that makes it page 1 is not.* The main page. Filters: category, region, type, free-text search. Sorted newest first, paginated. Filter changes swap the results list via htmx without a full reload. Needs: filter sidebar (drawer on mobile), result card, pagination, empty state, and an active-filter summary that can be cleared. **What exists today** is an unstyled list of the newest twenty published actions at `/actions`, built because the welcome email needs a destination (ADR-005) — no filters, no search, no htmx swapping, no pagination, and titles that are not yet links because the detail page is page 2. Those are absent rather than stubbed: a filter control that silently does nothing is worse than none. |
| 2 | **Action detail** | Title, category/region/type badges, full description, image, optional embedded video, author link, end date with remaining time, and a prominent outbound call-to-action button ("Sign the petition" etc., label depends on type). **The link is optional — needs a second variant with no CTA button at all.** Secondary: share, report. |
| 3 | **Poster profile** | Display name, bio, website link, list of that poster's published actions. |
| 4 | **About** | Static content page. |
| 5 | **Privacy policy / terms** | *Stub built, text and design yours.* Static; legally required (GDPR). Same template as About. `/privacy` now resolves — the registration consent checkbox links to it, and a consent whose policy link lands on a login form is not one you can defend. The copy is a truthful placeholder stating what registration stores; **the legal document is still owed.** |

### Authentication

| # | Page | Notes |
|---|---|---|
| 6 | **Register** | *Backend built, design yours.* Email, password, password confirmation, display name, consent checkbox for the privacy policy. |
| 7 | **Login** | *Backend built, design yours.* Email, password. Errors are deliberately vague ("wrong email or password"). |
| 8 | **Verification notice / result** | *Backend built, design yours.* "Check your inbox", plus the page the emailed link lands on. Three result states, and **none of them offers to resend** (ADR-002 cut the resend form): **confirmed** — success, link to log in; **already confirmed** — same reassuring tone, not an error, since a second click or a mail client prefetching the link is ordinary; **dead link** — the link expired or the unconfirmed account was purged after seven days, so the only action is a link back to Register, which re-issues a fresh email. The "check your inbox" state must also be what a user sees after registering an address that already has a confirmed account — identical page, identical wording, so the form cannot be used to discover who has an account here. **This page carries the only recovery there is — see below.** |
| 9 | **Unsubscribe confirmation** | Reached from a digest email link, **works without login**. One page: confirm, done. |

### Signed in

| # | Page | Notes |
|---|---|---|
| 10 | **Submit action** | The most complex form in the app, and the one worth designing first. Title, description, type (select), categories (multi, max 3), regions (multi, max 3), external URL, end date, image upload, optional video URL. Needs inline validation, character counters, and an explanatory state for `USER`s: "your action will be reviewed before publishing." |
| 11 | **Edit action** | Same form, pre-filled. |
| 12 | **My actions** | The user's own actions grouped or badged by status, with actions per row: edit, withdraw, re-publish, delete. This is the only place `PENDING` / `REJECTED` / `WITHDRAWN` are visible. A poster cannot leave `REJECTED` directly: editing a rejected action returns it to `PENDING`, so that row's button says "edit and resubmit", not "re-publish". |
| 13 | **Subscription preferences** | Pick categories and regions to follow; toggle the daily digest on/off. |
| 14 | **Edit profile** | Display name, bio, website, interface language. |
| 15 | **Account settings** | Change password; delete account behind a clear, scary confirmation (it permanently deletes their actions too). |

### Admin

| # | Page | Notes |
|---|---|---|
| 16 | **Approval queue** | Pending actions with approve / reject buttons. Utilitarian, not pretty. |
| 17 | **Reports queue** | Reported actions with reject / dismiss buttons. Rejecting is the takedown — there is no separate "hide". |
| 18 | **User admin** | Search a user; grant the `POSTER` role; disable an account. |

### System

| # | Page | Notes |
|---|---|---|
| 19 | **404 / 403 / 500 / 429** | Real pages in the site layout, not stack traces. 404 matters: deleted actions land here from old digest links — and from the welcome email, whose action links point at page 2 before page 2 exists. **429 joins the list:** the rate limiter on `/login` and `/register` (ADR-002) refuses through the error dispatch with a `Retry-After` header, precisely so that it inherits this page instead of carrying a user-facing string in Java. Until then a rate-limited user gets the whitelabel page, which tells them nothing — so this one needs copy as well as a layout: what happened, and that it will work again shortly. |
| 20 | **Report an action** | Small form: reason (select, includes "no longer live") + optional text. Modal or small page, submitted via htmx. |

### Emails (4)

Separate templates, and genuinely different work from web pages: table-based
layout, inline CSS, ~600px, light/dark-safe colours, and a plain-text
alternative. The unsubscribe footer belongs to the digest only — the other
three are transactional, and an unsubscribe link on a verification email is a
link to opt out of the thing the user is in the middle of doing.

1. **Email verification** — one button, minimal. **Built**, along with 4 and a
   shared `email/layout.html` carrying the header, footer and dark-scheme
   overrides. Each email is an HTML template plus a `.txt` twin of the same
   name; both render from the same model, and both are in the message bundles.
2. **Welcome** — sent after verification; the five newest active actions.
   **Built.** Structural placeholder like the others: a greeting, an intro, one
   block per action (type label, linked title, one abbreviated line) and a
   single call to action pointing at the listing. Three things must survive a
   redesign. The empty state is copy, not a gap — the first confirmed account
   on a fresh deployment sees no actions at all, and a silent hole where a list
   should be reads as a broken email. There is **no unsubscribe link**, by
   decision rather than omission (see the paragraph above this list). And there
   is no mention of the daily digest, because the job does not exist yet; that
   line is owed, and it lands with the job rather than with the design.
   **The actions are listed as text, not as links** — the detail page is page 2,
   and a first email carrying five links to a page that does not exist is worse
   than five plain headings. The call to action is the email's only link. When
   page 2 lands the link goes into this template and the listing together.
3. **Daily digest** — the flagship email: new actions matching the user's
   subscriptions, each as a title, category/region badges, one line, a link.
   Must survive Gmail, Outlook and Apple Mail.
4. **"You already have an account"** — sent when someone registers an address
   that already has a confirmed account. **Built.** Not in the original three,
   and not optional: the registration form must answer identically for every
   address (page 6), which leaves the mailbox as the only safe place to tell
   the owner anything. Deliberately reassuring rather than alarming — the
   likely reader is the owner, who forgot. It offers **login, not a password
   reset**, because reset is v1.1 (section 10); that is the one place the
   missing reset is visible to a user, and this email is where it goes when it
   lands.

#### Page 8 must say what to do when the email does not arrive

**This is the most load-bearing copy in the MVP, and it is the one piece of it
that cannot be left to the layout.** There is no resend form and there will not
be one (ADR-002): registering again over an unconfirmed address replaces the
row and sends a fresh link, so it already *is* a resend. That is only true for
a user who knows it, and right now the page would say "check your inbox", the
email would not arrive, and nothing would tell them what to do next. The
registration also cannot be undone from their side, so the dead end is total.

The strings exist and are now on the page — use them, do not write new ones:

| Key | Where |
|---|---|
| `verification.notice.heading` | the page heading |
| `verification.notice.sent` | one argument: the address **as the user typed it**. Echoing it back is how a typo gets caught, and it leaks nothing — they typed it. |
| `verification.notice.expiry` | one argument: the number of days, passed in from `TokenPurpose.EMAIL_VERIFICATION`, never typed into the template |
| `verification.notice.spam` | the first thing to check, because a new sending domain is exactly what lands in spam |
| `verification.notice.resend` + `verification.notice.resendLink` | the recovery: register again with the same address. The link goes back to page 6. |

Two things not to do with it. Do not present it as an error state — most people
find the email and never read this. And do not put the recovery behind a
disclosure widget or a "having trouble?" link: the person who needs it is the
person for whom the page already appears to have failed, and they will not go
looking.

The **dead link** state reuses `verification.notice.resend` and
`verification.notice.resendLink` for the same reason, with its own heading.

The two emails that exist are a starting point, not a design — redesign them freely.
What must not change: every string stays in `messages_bg` / `messages_en`, the
`.txt` twin stays in step with its HTML, and nothing user-supplied goes in a
subject line (a newline in a display name is header injection).

---

## 5. Reusable fragments

Worth designing once and reusing:

- Base layout: header, nav, footer, language switcher, flash-message area
- Action card (list version) and action row (compact, for "my actions" and emails)
- Category / region / type badge; status badge
- Filter sidebar and its mobile drawer
- Pagination
- Form field with label, hint, error, and character counter
- Empty state (no results, no actions yet, no subscriptions yet)
- Confirmation dialog (delete, withdraw)
- Flash messages: success, error, info
- Outbound-link button with an external-link indicator

## 6. States every list and form needs

- **Loading** — htmx swaps take a moment; use `hx-indicator`.
- **Empty** — filters match nothing; poster has no actions yet.
- **Error** — validation errors inline on the field, server errors as a flash.
- **Disabled/pending** — buttons during submit, to prevent double posts.
- **Logged out vs logged in vs admin** — the same page can show different
  controls. Visibility is enforced server-side; the frontend just needs the
  variants.

---

## 7. Design direction

- **Turquoise** is the brand colour; the rest is open.
- Tone: serious and trustworthy, not playful. The content includes animal
  cruelty, disability rights and political issues. NGOs need to feel it's a
  credible place to publish.
- The product promise is "five good minutes" — low effort, quick action. The
  path from landing to clicking through to a petition should feel short.
- Content is dense and text-heavy: readable Cyrillic typography matters more
  than illustration.
- Accessibility is not optional here given the audience: keyboard navigation,
  visible focus states, real contrast ratios, `alt` text, labelled form fields.
- Light and dark mode both, if it's not much extra work.
- Icons need a permissively licensed set (e.g. Lucide, Heroicons). No
  copyrighted or brand imagery.

**Images in the action list.** Images are optional on submission, so most
actions won't have one, and images are served from the database through a
controller — every thumbnail is a request. Default: **text-only cards in the
list, with the type icon carrying the visual weight, and one featured action at
the top of the page with an image.** Full images live on the detail page. If
you'd rather do it differently after seeing real content, propose it.

**Content for mockups:** use realistic Bulgarian text, not lorem ipsum —
Cyrillic changes line length and rhythm. There will be ~40 seed actions.

---

## 8. Ways of working — your call

These are the contributor's decisions, with context rather than instructions.

**Templates or mockups.** Either deliver Thymeleaf templates directly, or
deliver flat HTML/CSS that Georgi converts. Direct templates avoid a
translation step but mean running the app locally and learning Thymeleaf's
attribute syntax. Flat HTML is faster to start but drifts from the converted
version after a couple of revision rounds. A phased split works well: flat
HTML for the design system and the first pages, Georgi converts those into the
base layout and fragments, then work directly in templates from there.

**Design tool.** Figma, wireframes, or straight to code — whichever is
fastest. The app is text-heavy and CRUD-shaped with a fixed brand colour, so
there isn't much for high-fidelity design to discover. Nobody will maintain a
Figma file in parallel with the code, so if one exists, treat it as
disposable.

**Suggested early deliverable: a living style guide page** at a dev-only route
rendering every component — buttons, badges, form fields, cards, flash
messages, empty states — in both languages and both themes. It lives in the
repo, so it can't go stale, it's the fastest way to check work without
clicking through five pages, and it demos well.

---

## 9. MVP list

Everything below ships in the first release.

- Action list with category / region / type filters and text search
- Action detail with outbound CTA, image, optional video embed
- Poster profile page
- Register, log in, log out, email verification
- Submit / edit / withdraw / delete own actions, with an approval state
- "My actions" with status badges
- Subscription preferences (categories, regions, digest toggle)
- Profile editing, password change, account deletion
- Report an action
- Admin: approval queue, reports queue, grant `POSTER`, disable a user
- Bulgarian / English interface switching
- Responsive layout + web app manifest (installable)
- Error pages: 404, 403, 500
- 4 email templates (verification and "you already have an account" are built;
  welcome and the daily digest are not)
- Static pages: About, Privacy

## 10. v1.1 — before real users

Deliberately out of the first build, but needed before the site is promoted to
an actual audience.

- **Password reset ("forgot your password?").** Two pages and one email
  template. Until it exists, a user who forgets their password is locked out
  permanently, since the email address is unique and can't be reused. Cheap to
  add — no database change and it reuses the signed-link mechanism built for
  email verification — so deferring costs nothing in rework.
- **Quarterly re-confirmation** that an action is still live.

## 11. v2 list

Not now. Don't design for these, but don't paint us into a corner either.

- **"I did it"** — users privately mark actions as completed. Private by
  design; a public history would need its own consent flow.
- **Ranking / trending** — currently newest-first only.
- **Hashtags** — free-form tags alongside fixed categories.
- **Articles hub** — longer-form content about causes.
- **Activism calendar** — dated events and campaigns.
- **Web push notifications**, and offline support via a service worker.
- **Native mobile app.**
- **Avatars** for posters (deferred with the image work).
- **Organizations** — multi-member NGO accounts, rather than one user per NGO.
- **Two-facet taxonomy** — beneficiary and issue as separate filters.
- **Poster-facing stats** — how many people clicked through.

---

## 12. Working agreement

- Branches and pull requests; Georgi reviews everything, because he has to be
  able to explain every line of the project.
- The app runs locally with one Docker Compose command (ready in week 1). A
  frontend contributor should never need a local Java toolchain configured by
  hand.
- Real pages only exist from roughly week 4. Useful work before that: the
  design system (colour tokens, typography scale, spacing), the base layout,
  wireframes for the list and detail pages, and the email templates.
- README credits contributors by name. Licensing to be agreed before the first
  substantial contribution.
