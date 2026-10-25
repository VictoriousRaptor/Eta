# In-app webpage links implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan inline, followed by a fresh read-only whole-branch review.

**Goal:** User-clicked HTTP(S) links open with Android System WebView inside Eta by default; a General settings switch restores external handling.
**Architecture:** A non-exported standalone Activity owns its WebView. One link opener routes ordinary app links and the themed Compose UriHandler, including overlays. Agent automation keeps its existing WebView. Explicit browser/download actions and provider authorization retain external handling.
**Tech Stack:** Kotlin, Compose, Android WebView, existing local SharedPreferences and Eta preference controls.
**Spec:** User request in this chat (2026-10-25); defaults and implementation choices described before implementation.

## Global constraints

- Start from local main 5e9d3a7 on codex/in-app-webview-links, with no custom changes.
- Preserve all pre-existing custom index/worktree changes. Merge only the reviewed feature into custom, then build a Release APK signed with the local debug key.
- Do not change version, dependencies, production signing, or device state; do not push.
- Default on; local preference independent of Xposed; existing provider authentication and explicit external-browser commands stay external.

## Review focus

- Preference off and non-HTTP links keep existing system routing.
- Redirects stay inside WebView; file/content access is disabled and page-initiated external actions require a main-frame gesture.
- Browser history, rotation and Activity destruction retain history and release the owned WebView.
- Markdown links in main UI and overlays receive the same handler using its existing Compose host without changing custom lifecycle behavior.
- Feature branch ancestry contains main only; merged APK contains committed custom plus this feature, excluding unrelated uncommitted edits.

## Task 1: Link routing and local setting

- [ ] Add behavior tests for HTTP(S), disabled preference, external schemes, non-Activity context, and non-exported Activity.
- [ ] Observe missing behavior, implement a shared link opener and persisted default-on setting, and wire the themed UriHandler and settings URLs.
- [ ] Add English, Simplified and Traditional Chinese strings.

## Task 2: Independent system WebView

- [ ] Add tests for navigation restrictions and external app gesture gating.
- [ ] Implement a non-exported Activity with title/address, back history, reload, explicit external browser action, saved state and cleanup.
- [ ] Disable file/content access; no JS bridge or SSL bypass. Allow JavaScript and DOM storage for normal websites.

## Task 3: Review, integration and package

- [ ] Run focused Robolectric tests, compile and lint; review whole diff independently and address actionable findings.
- [ ] Commit only intended files, merge to custom preserving the original dirty workspace, and verify main/feature/custom ancestry.
- [ ] Build the committed custom state in isolation using the approved project JDK/cache and existing debug signing flow; verify signature, package flags, alignment and hash.
- [ ] Deliver APK, commit IDs, review/test evidence and device-validation limits.
