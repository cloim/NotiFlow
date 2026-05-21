---
title: "Avoid Android org.json in JVM unit-testable code"
date: 2026-05-21
source: verification
scope: project
tags:
  - android
  - unit-test
  - json
  - jvm
---

## Trigger

`GitHubReleaseUpdateTest` failed under `testDevDebugUnitTest` with `Method optString in org.json.JSONObject not mocked`.

## What Went Wrong

The release parser used Android framework `org.json` APIs in code that was intended to run in local JVM unit tests.

## Correct Pattern

Keep pure parsing utilities on JVM-friendly libraries already available in the project, such as Gson, or move Android JSON usage behind instrumentation-only code.

## How To Catch Earlier

Run the focused unit test immediately after adding parsing code:

```powershell
.\gradlew.bat testDevDebugUnitTest --tests com.vibe.notiflow.GitHubReleaseUpdateTest
```

## Applies Next Time When

New Android production code is intended to be covered by local JVM unit tests and needs JSON parsing.

## Should Update Skill?

no
