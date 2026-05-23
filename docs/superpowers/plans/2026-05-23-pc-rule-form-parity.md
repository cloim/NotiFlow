# PC Rule Form Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the NotiFlow PC settings create/edit rule form match the in-app rule form behavior while preserving the PC page layout.

**Architecture:** Keep `PcSettingsServer` as a self-contained HTML/JS page. Replace only the rule form UI with vanilla JS equivalents of the app's package picker, grouped condition builder, token controls, diff preview, and payload generation.

**Tech Stack:** Kotlin embedded HTML string, vanilla browser JavaScript, existing PC settings JSON APIs, Node-based contract verification.

---

### Task 1: Add PC Form Contract

**Files:**
- Create: `web/scripts/verify-pc-rule-form-parity.mjs`
- Modify: `web/package.json`

- [ ] Add a Node verification script that reads `PcSettingsServer.kt` and requires app-equivalent form markers: package picker, search, include-system toggle, condition groups, condition preview, token removal, diff box, and no `conditionRows` textarea.

- [ ] Expose it as `npm run test:pc-rule-form`.

- [ ] Run `npm run test:pc-rule-form`; expected result before implementation is FAIL because the current PC form uses `conditionRows`.

### Task 2: Replace The PC Rule Form

**Files:**
- Modify: `app/src/main/java/com/vibe/notiflow/pc/PcSettingsServer.kt`

- [ ] Keep the existing two-column PC layout.

- [ ] Replace the `aside` form markup with app-equivalent fields: package picker button, optional rule name, grouped conditions, webhook URL/method, priority, headers, payload template, token, remove-token checkbox, diff preview, and save/reset actions.

- [ ] Add a picker dialog for installed apps with search and system-app toggle. It must call `/api/apps?includeSystem=true|false`.

- [ ] Port the app's condition group state functions to vanilla JS: create groups, add/remove groups, add/remove conditions, set intra-group and next-group operators, build `conditionExpression.rows`, validate rows, and show a preview.

- [ ] Update `editRule` and `saveRule` so generated payloads match the app payload contract.

### Task 3: Verify And Ship

**Files:**
- Modify only files touched by Tasks 1 and 2 unless validation exposes a direct issue.

- [ ] Run `npm run test:pc-rule-form`.

- [ ] Run `npm run test:pc-settings-server`.

- [ ] Run `.\gradlew.bat testDevDebugUnitTest lintDevDebug :app:compileDevDebugKotlin :app:compileProdDebugKotlin`.

- [ ] If browser automation is available, open the PC settings mock in the in-app browser and verify the picker and condition builder render. If unavailable, state the blocker.
