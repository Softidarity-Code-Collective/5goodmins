# Frontend Brief — 5 Good Minutes (5goodmins.com)

For the frontend contributor. Read alongside `ARCHITECTURE.md`; where the two
disagree, `ARCHITECTURE.md` wins and this file gets corrected. Last reconciled
against ARCHITECTURE.md Rev 28 (2026-11-05): **the repository is now set up for
you to start.** One Compose command runs everything, templates and CSS reload
on save, the static asset paths exist and are served, htmx is vendored and
wired to the CSRF token, the Content-Security-Policy is decided (section 1 —
read it before you write a style attribute), every enum has Bulgarian and
English labels, there is development content to design against, and there is a
style guide page at `/dev/styleguide` waiting to be filled in. Section 0 is the
first hour.

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

## 0. Start here

**Run it.** Docker is the only requirement; no Java toolchain, no npm.

```bash
docker compose --profile app up --build
```

First build takes a few minutes. Then:

| | |
|---|---|
| The site | http://localhost:8080 |
| **Style guide** | http://localhost:8080/dev/styleguide |
| Mailpit, which catches every outgoing email | http://localhost:8025 |

**Templates, CSS and JavaScript reload on save.** No restart, no rebuild — the
development profile reads them straight out of `src/main/resources`. Java
changes still need a restart.

**Log in** by registering at `/register` and clicking the link in Mailpit —
twenty seconds, and it is the real flow. The two demo authors who own the seed
content cannot log in on purpose: that content ships to the live site, so a
password written down here would be a password on the live site (ADR-021).

**Where things are:**

| | |
|---|---|
| Pages | `src/main/resources/templates/` |
| Shared fragments | `src/main/resources/templates/fragments/` |
| The base layout | `src/main/resources/templates/layout.html` |
| CSS, JS, fonts, images | `src/main/resources/static/{css,js,fonts,images}` |
| Text, Bulgarian | `src/main/resources/messages_bg.properties` |
| Text, English | `src/main/resources/messages_en.properties` |
| The style guide page | `src/main/resources/templates/dev/styleguide.html` |

**What actually exists today, and it is less than the page inventory below
suggests.** Six pages have a backend: register, login, the two verification
states, the privacy stub, and a **placeholder action listing at `/actions`**
that has no filters, no search and no paging — it exists because the welcome
email had to link somewhere. **There is still no home page**: following the
logo in the header redirects to the login form until page 1 proper is built.
Everything else in section 4 is a specification, not a route.

So the work that is genuinely unblocked right now is:

1. The design system — colour, type scale, spacing — and the Cyrillic
   typeface. Put it in `static/css/main.css` and show it off in the style
   guide.
2. The base layout and the fragments in section 5. Four of them already exist
   as unstyled placeholders (layout, flash) or as lists on the style guide
   page (badges); the rest are yours from scratch.
3. The six pages that exist, plus the five error pages — the four named
   statuses and the fallback at `templates/error.html`. All five are
   structural placeholders with their copy written; the design is what is
   missing, not the strings.
4. The two built email templates.
5. Wireframes for the action list and detail pages, which is where the design
   is actually decided, against the seed content described in section 3.

**Everything you touch is a placeholder unless its own comment says
otherwise.** Each template starts with a comment block saying what the backend
depends on. That list is short and specific; outside it, delete and rebuild
freely.

### Still owed by the backend, so you do not design around a gap twice

- **Dates and times on screen.** Everything is stored as an instant in UTC.
  There is no display timezone decision (it will be Europe/Sofia), no
  formatter, and no strings for "ends in 3 days". Page 2 and page 12 need
  them; they arrive with the action list rather than before it. Leave a slot,
  do not invent a format.
- ~~**The filter and pagination URL contract** for page 1.~~ **Decided in
  full: ADR-022.** Parameters, facet semantics, the swap target and the
  fragment name are in that ADR; page 1's row below is the short version. The
  two answers that change what you draw: **facets are multi-select**, so the
  sidebar is checkboxes rather than dropdowns and the active-filter summary
  can hold a dozen chips; and **pagination is numbered pages**, so the
  pagination fragment is page numbers rather than a "load more" button.
- **Images.** ADR-006 settled on storage in Postgres served through a
  controller, but neither the table nor the upload exists yet. Section 7's
  default — text-only cards, one featured action with an image — is what to
  design for now.
- **Password reset.** v1.1, two pages and an email, not in this build
  (section 10).

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

htmx 2.0.11 is **vendored** at `static/js/htmx-2.0.11.min.js` and loaded by the
layout; the version is in the filename. Not a CDN — see the policy below. To
upgrade it, drop in the new file and change the one `<script>` tag.

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

**Content-Security-Policy: `default-src 'self'` (ADR-020).** This one is
decided and it constrains how you write every page, so it is the paragraph to
read twice.

- **No inline `style="..."` attributes and no inline `<style>` blocks.** All
  CSS in files under `static/css`.
- **No inline `<script>` and no `onclick` handlers.** All JavaScript in files
  under `static/js`. htmx attributes are markup, not script, and are fine.
- **No third-party anything**: no CDN, no Google Fonts stylesheet, no
  hotlinked image, no embedded widget. Self-host it instead — that is what
  `static/fonts` is for.
- `img-src` allows `data:`, so an icon set can be inlined.

A browser enforcing this fails **silently**, with only a console message, so
the symptom of breaking it is a page that looks unstyled for no visible
reason. If something you need genuinely requires loosening the policy, say so
and we change the ADR — do not work around it.

**What it forbids in htmx specifically**, since these are the attributes you
would otherwise reach for first: `hx-on:click="..."` and its siblings, the
`js:` prefix on `hx-vals` / `hx-headers`, and event filters such as
`hx-trigger="click[ctrlKey]"`. All three evaluate a string as code. The layout
sets `allowEval: false` in htmx's config so htmx declines them with a readable
console message rather than leaving you with a policy violation reported from
inside a minified file. Everything else in htmx — `hx-get`, `hx-post`,
`hx-target`, `hx-swap`, `hx-trigger` without a filter, `hx-indicator`,
`hx-confirm` — is unaffected. `hx-indicator` has one wrinkle that is already
handled: htmx would normally inject its `.htmx-indicator` rules as an inline
`<style>` block, which the policy blocks, so that injection is turned off and
the three rules live in `main.css` — design them, don't delete them.

**And the workarounds that do work**, because "no inline styles" sounds more
limiting than it is:

- A value computed per element (a progress width, a brand colour from data)
  — set it from a file under `/js` with `element.style.setProperty(...)`.
  The policy blocks the *attribute* in the HTML, not the CSSOM.
- A handful of variants — utility classes, or a `data-` attribute the
  stylesheet selects on. `data-` attributes are markup and always allowed.
- Dark mode — `prefers-color-scheme` in the stylesheet, and a toggle that
  flips an attribute on `<html>` from a file under `/js`.
- Inline SVG icons — fine as markup; give them `class` and `fill="currentColor"`
  rather than a `style` attribute.

**Static assets** live in `src/main/resources/static/` and are served from the
root: `static/css/main.css` is `/css/main.css`. Four prefixes are public
without a login — `/css`, `/js`, `/images`, `/fonts` — and a fifth path is not,
so anything outside them will answer a redirect to the login form rather than
a file.

**CSRF.** Spring Security blocks POSTs without a token. The token is in a
`<meta>` tag in the base layout, and `<body>` carries `hx-headers` so every
htmx POST sends it. Plain forms get a hidden input automatically, as long as
the form uses `th:action` rather than `action`. Without this everything
silently returns 403 — the style guide has a button that posts, which is the
fastest way to check the wiring survived your rewrite of the layout.

**The layout is a parameterised fragment**, not the layout dialect (which is
not a dependency here). A page declares:

```html
<html th:replace="~{layout :: page(#{some.title}, ~{::main})}">
```

and passes its own `<main>` in. That `<main>` must carry `id="main"` — the
skip link in the layout points at it, and `th:replace` swaps the layout's
element for yours, id and all. If you would rather use the layout dialect, or
want a third parameter for per-page CSS, that is a conversation, not a
unilateral change: both are dependencies or contracts other templates inherit.

**Flash messages** come from the server as a key, not as text, and are
rendered by `fragments/flash.html` in the layout above the page content. The
three levels are `SUCCESS`, `ERROR` and `INFO`. Styling is yours; the
`role`/`aria-live` pair is not optional, because a message that appears after
a redirect is announced to a screen reader only if the container says so.

**Content is Bulgarian, code is English.** Category names, regions and statuses
are English constants in the code (`HUMAN_RIGHTS`, `STARA_ZAGORA`) and are
translated through the properties files. Never display the constant.

All 54 of them now have both languages, and the key is built from the value:

```html
<span th:text="#{'category.' + ${category}}">Природа</span>
```

The prefixes are `category.`, `region.`, `action.type.`, `action.status.` and
`action.cta.` — the last being the outbound button's label, one per type,
because "Sign the petition" is wrong on a survey. The lists themselves are on
the model of every page as `${categories}`, `${regions}`, `${actionTypes}` and
`${actionStatuses}`, so a filter sidebar or a select never hardcodes them. The
style guide renders all five sets.

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
| 1 | **Action list / home** | *Placeholder built, everything that makes it page 1 is not — but the URL contract now is (ADR-022).* The main page. Filters: category, region, type, free-text search. Sorted newest first, paginated. Filter changes swap the results list via htmx without a full reload. Needs: filter sidebar (drawer on mobile), result card, pagination, empty state, and an active-filter summary that can be cleared. **The contract to build against:** `GET /actions?category=&region=&type=&q=&page=` — repeatable parameters carrying enum constant names (`HUMAN_RIGHTS`, never the label), target `#action-results`, swap `innerHTML`, fragment `action/list :: results`, `hx-push-url="true"`, and the same route serves the fragment when htmx asks. Facets are **multi-select checkboxes** (OR within a facet, AND across), pagination is **numbered pages**, and results are newest-first except with a search term, where they come back by relevance. The filter form is a real `<form method="get">` with `hx-get` on the form, so it works with htmx absent. The sidebar stays **outside** the swap (re-rendering it mid-keystroke moves focus); the active-filter summary goes **inside** it, so it cannot disagree with the results. **What exists today** is an unstyled list of the newest twenty published actions at `/actions`, built because the welcome email needs a destination (ADR-005) — no filters, no search, no htmx swapping, no pagination, and titles that are not yet links because the detail page is page 2. Those are absent rather than stubbed: a filter control that silently does nothing is worse than none. |
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
| 19 | **404 / 403 / 500 / 429 / fallback** | *All five built, design yours.* **There are five, not four:** `templates/error.html` is the fallback every other status resolves to — a 405 from a crawler, a 415, a 400 from a hand-edited query parameter — and it lives at the templates root rather than in `error/`, because that is the one Boot resolves by view name. Style it with the others; it is the page an unanticipated status gets. Real pages in the site layout, not stack traces, at `templates/error/<status>.html` — the filename is what Spring Boot resolves, so renaming the file is what breaks it. 404 matters: deleted actions land here from old digest links, and from the welcome email, whose action links point at page 2 before page 2 exists. **429 is the odd one and worth reading before you restyle it:** it is the only error page reached by a POST rather than a link, because the rate limiter on `/login` and `/register` (ADR-002) refuses through the error dispatch with a `Retry-After` header, precisely so the user-facing string lives in the bundle rather than in Java. Its copy deliberately names **no interval** — the reason is in ADR-002 and it is a security one. Do not add a countdown or a "try again in N minutes" to the design. Caveat on all four: until more public paths are named in `SecurityConfig`, an unknown URL redirects an anonymous visitor to the login form instead of reaching the 404 page. |
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

Worth designing once and reusing. Two of them exist as unstyled placeholders
you can rebuild — the base layout and the flash message — and the badges are
already rendered on the style guide from the real message bundles; the rest
are yours from scratch.

- Base layout: header, nav, footer, language switcher, flash-message area
  *(placeholder exists: `layout.html`, `fragments/flash.html`)*
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

**Content for mockups:** the seed content exists — 31 actions in realistic
Bulgarian, loaded in every environment (ADR-021), readable in
`src/main/resources/db/seed/V100__seed_demo_data.sql` and copy-pasteable into
a mockup. It is chosen to include the cases that break layouts rather than 31
comfortable ones: an action with **no external link**, one that is expired, one
in each of the three non-public statuses, one with three regions, one with a
video, and one with a title at the full 200 characters and a description long
enough to break a card that assumed two lines. Design against those, not
against the short ones.

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

**The living style guide exists and is waiting for you**, at
`/dev/styleguide` (development only), from
`templates/dev/styleguide.html`. It currently renders every badge, the three
flash levels, an empty state, a form field and an htmx round trip — all
unstyled, from the real bundles and the real model. It is the fastest way to
check work without clicking through five pages, it cannot go stale because it
lives in the repo, and it demos well. Grow it as you add components; its own
headings are developer-facing English and deliberately not in the bundles.

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
- The app runs locally with one Docker Compose command — `docker compose
  --profile app up --build`, section 0. No local Java toolchain, and templates
  and CSS reload on save.
- Real pages only exist from roughly week 4. Useful work before that: the
  design system (colour tokens, typography scale, spacing), the base layout,
  wireframes for the list and detail pages, and the email templates.
- README credits contributors by name.
- **Licensing: the repository is under the PolyForm Noncommercial License
  1.0.0** (see `LICENSE`) — worth reading before you spend a weekend on a
  design system rather than after. If that does not work for you, say so now
  and we talk about it.
