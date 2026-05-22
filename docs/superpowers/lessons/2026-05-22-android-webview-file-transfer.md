---
title: "Android WebView file transfer needs native handlers"
date: 2026-05-22
source: review
scope: project
tags:
  - android
  - webview
  - file-transfer
  - review
---

## Trigger

Final review found that the rule JSON import/export UI used browser-style file APIs, but the Android WebView shell did not provide native file chooser or file save handling.

## What Went Wrong

The implementation assumed `<input type="file">` and Blob anchor downloads would work reliably inside the Android app. In this project, `MainActivity` owns the WebView shell, so file input requires `WebChromeClient.onShowFileChooser`, and export needs a native save path such as `ACTION_CREATE_DOCUMENT` or an equivalent bridge.

## Correct Pattern

When adding Web UI file import/export in NotiFlow, implement both layers:

- Web UI: keep browser fallback for development mode.
- Android shell: add native file chooser support for imports.
- Android shell: add a save bridge or download handler for exports.
- Verify pending callbacks or pending file content are cleared on cancel and launch failure.

## How To Catch Earlier

During review, ask whether each browser file API has a corresponding Android WebView handler. Contract checks should look for `onShowFileChooser` for imports and `ACTION_CREATE_DOCUMENT` or a save bridge for exports.

## Applies Next Time When

Use this lesson whenever a NotiFlow WebView feature adds file input, file download, Blob URLs, anchor `download`, or any browser API that depends on host browser chrome.

## Should Update Skill?

no
