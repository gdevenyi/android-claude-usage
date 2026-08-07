# Claude Usage for Android — Design

Settled 2026-08-07 via grilling session. A native Android port of the
[plasma-claude-usage](https://github.com/gdevenyi/plasma-claude-usage) concept:
show Claude subscription usage in a home-screen widget and an ongoing
notification. No detail screen — the widget and notification ARE the app.

## Auth

- In-app OAuth PKCE login with Claude Code's public client ID
  `9d1c250a-e61b-44d9-88ed-5944d1962f5e`.
  - Authorize: `https://claude.ai/oauth/authorize`
  - Token exchange + refresh: `https://console.anthropic.com/v1/oauth/token`
    (fallback domain: `platform.claude.com` — Anthropic is renaming)
  - Manual paste-code redirect (`https://console.anthropic.com/oauth/code/callback`),
    no localhost listener.
  - PKCE S256; `state` = the PKCE verifier (flow quirk). Form-encoded POST
    (JSON gets 400).
- The phone owns an **independent grant** and refreshes it itself
  (`grant_type=refresh_token`; rotation is single-use — persist the new
  refresh token immediately). Never copy the desktop's
  `~/.claude/.credentials.json`: shared-token refresh collisions log one
  device out (documented Claude Code failure cluster).
- Accepted risk: unofficial reverse-engineered flow; could break or be
  restricted. Relevant to eventual Play review.

## Data

`GET https://api.anthropic.com/api/oauth/usage`

Headers: `Authorization: Bearer <token>`, `anthropic-beta: oauth-2025-04-20`,
and **required** `User-Agent: claude-code/<version>` (without it: instant
429 bucket). Rate limit is per-token; poll ≥15 min.

Fields used (verified against the live endpoint 2026-08-07): the response
carries a `limits` array, and that is what the app parses —

- `kind: "session"` → the 5h window
- `kind: "weekly_all"` → the 7d window
- `kind: "weekly_scoped"` → per-model, name at `scope.model.display_name`

each with `percent` and `resets_at`. The legacy `five_hour` / `seven_day`
objects still appear and are used as a fallback, but the legacy
`seven_day_sonnet` / `seven_day_opus` fields now come back **null**, so the
per-model breakdown only works via `limits`.

The plan badge ("Max 5x") is not in this response. It comes from
`GET /api/oauth/profile` (same auth headers), field
`organization.rate_limit_tier` — e.g. `default_claude_max_5x`, which the app
strips and title-cases. That endpoint also returns name and email, which the
app neither stores nor shows. The plan only changes when the subscription
does, so it is fetched once and cached until logout.

Dropped from the plasma widget: transcript scanning (no local Claude Code),
custom base-URL/proxy mode, i18n (English only).

## Surfaces

- **Widget**: one Glance widget using `SizeMode.Exact`. Four tiers, each
  verified on a Pixel 8 at the launcher's row heights:

  | Rows | Shows |
  |---|---|
  | 1 | logo + the two headline percentages |
  | 2 | labelled bars with right-aligned percentages |
  | 3 | the same plus reset countdowns |
  | 4+ | header with plan chip, by-model section, updated-at |

  Tap = refresh in place. Layout follows the plasma popup: right-aligned
  percentages, window length in the label ("Session (5h)"), and
  "resets 17:00 · in 2h 38m".
- **Notification**: ongoing, updated by WorkManager (no foreground service;
  a swipe hides it until next refresh). Both views are custom RemoteViews
  inside `DecoratedCustomViewStyle`, so they carry real progress bars in the
  severity colours while the system still draws its own icon/name/time header.
  Collapsed = the headline percentages, session bar, session countdown;
  expanded = both windows with bars and reset times, the by-model row, and
  the updated-at line. Tap = refresh in place.
- **Activity**: settings only — Log in/Log out with Claude (status + plan),
  notification on/off, refresh interval 15/30/60 min.

## Styling — Material 3

Both surfaces follow the M3 token conventions rather than ad-hoc values:

- **Type**: the M3 scale (title/body/label roles) at weights 400 and 500 only.
  M3 has no bold step, so hierarchy comes from size. The scale is *fixed* in
  M3, so the widget only nudges it up to 1.5× on roomy placements — beyond
  that the content stops fitting.
- **Spacing**: the 4/8/16dp grid. Container padding is a fixed 16dp.
- **Color**: role-based via `GlanceTheme`, so chrome and text follow the
  user's Material You scheme. Severity is the deliberate exception: M3 has no
  "success" role, and mapping it onto the accent roles cost the green/amber/red
  reading that is the whole point of the widget. It uses traffic lights, as
  `@color/usage_ok|warn|crit` with a `values-night` variant so the tones suit
  a light or dark background.
- **Shape**: system widget corner radius for the card, pill for the plan chip.
- **Progress**: 4dp track, per the M3 linear indicator.
- The settings screen uses Material 3 components (`Theme.Material3.DayNight`,
  `MaterialButton`, `MaterialSwitch`, outlined text fields) with
  `DynamicColors` applied so it matches the widget.

## Behavior

- WorkManager periodic refresh, default 15 min (platform floor).
- Cache last good response; show data age when stale; dead token →
  "log in again" state in widget/notification.
- Colors: green <50%, yellow <80%, red ≥80%.

## Stack & release

- Kotlin, Jetpack Glance, minSdk 33 (Android 13+).
- applicationId `io.github.gdevenyi.claudeusage`, app name "Claude Usage".
- GPL-3.0-or-later.
- Sideload (adb) for testing; eventually F-Droid and Play Store.
- Build on this machine (CachyOS): Android SDK at `~/Android/Sdk`
  (platform 35, build-tools 35), Gradle wrapper in the repo.

## Verified on device (Pixel 8, Android 17 / API 37)

- The PKCE login works end to end; the app fetches, caches and displays live
  usage in both surfaces, and tap-to-refresh repaints both.
- **Independent grants confirmed empirically**: after the phone logged in,
  the desktop's own token still worked. The shared-copy collision the design
  avoids is real, but separate logins do not disturb each other.

## Platform notes that cost us a debugging round each

- **Glance containers hold at most 10 direct children** and silently drop the
  rest. A section that emits several siblings must be wrapped in its own
  Column, or the tail of the widget vanishes with no error.
- **State read outside `provideContent` is captured once.** Read the store
  inside the composition or the widget never updates after a refresh.
- `defaultWeight()` is Row/Column-scope only; a helper composable that uses it
  must be declared as a `ColumnScope`/`RowScope` extension.
- **Nothing in a Glance layout shrinks to fit** — content that exceeds the
  widget is clipped at both ends, silently. So the size tiers are chosen by
  comparing available height against each tier's measured content height,
  with margin, and the scale factor keys off width only. Keying it off height
  as well grew the content faster than the space it gained.
- **`setProgress` is not usable here**: on the standard notification template
  it draws in the system accent (there is no tint API) and it *replaces* the
  content text rather than adding to it. A custom collapsed view gets both a
  tinted bar and the text — but it must stay under roughly 48dp of content or
  the last line is clipped, so its margins are 2dp.
- `RemoteViews.setColorStateList(id, "setProgressTintList", colorRes)` is how
  a progress bar gets tinted from a colour resource (API 31+), which keeps the
  `values-night` variants working inside the notification.
- **An app upgrade kills the Glance session** ("No session available for key
  appWidget-N") and the widget stays on its loading spinner until something
  updates it. Sideloading makes that every update, so opening the app now
  triggers a refresh, which repaints it.
- Edge-to-edge is mandatory since Android 15 and the opt-out is gone in 16+,
  so the activity applies `systemBars` insets itself.
