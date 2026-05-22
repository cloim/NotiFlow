# Rule JSON Import and Export Design

## Goal

Add JSON-based rule export and import for NotiFlow. Users can choose which rules to export, optionally include webhook tokens, and import exported rules as new rules without modifying existing rules.

## Scope

- Export selected rules to a JSON file.
- Let users choose whether exported JSON includes plaintext webhook tokens.
- Import rules from a JSON file by adding them as new rules.
- Ignore imported rule IDs to avoid overwriting existing rules.
- Keep existing logs, app settings, theme preference, and PC settings server config out of this feature.

## User Flow

1. User opens the rule export/import controls from the UI.
2. User chooses `룰 내보내기`.
3. App shows an export sheet with the current rule list.
4. All rules are selected by default.
5. User can select or clear individual rules, use `전체 선택`, or use `전체 해제`.
6. User can enable `토큰 포함` to include webhook tokens in plaintext JSON.
7. Export is disabled when zero rules are selected.
8. App downloads a `.json` file containing only selected rules.
9. User chooses `룰 가져오기` and selects a JSON file.
10. App validates the file and adds all imported rules as new rules.
11. App refreshes the rule list and shows the number of imported rules.

## JSON Format

The export file uses an app-owned versioned envelope:

```json
{
  "schemaVersion": 1,
  "app": "NotiFlow",
  "exportedAt": 1710000000000,
  "includeSecrets": false,
  "rules": []
}
```

Each rule is normalized to the same input shape accepted by the existing `createRule` bridge path:

```json
{
  "id": 12,
  "name": "Example rule",
  "packageName": "com.example.app",
  "conditionOperator": "AND",
  "conditions": [
    { "type": "text.contains", "value": "OTP" }
  ],
  "conditionExpression": {
    "rows": [
      { "type": "text.contains", "value": "OTP", "openParen": 0, "closeParen": 0 }
    ]
  },
  "enabled": true,
  "priority": 100,
  "webhook": {
    "url": "https://example.com/webhook",
    "method": "POST",
    "headers": { "X-App": "NotiFlow" },
    "payloadTemplate": "{\"text\":{{text}}}"
  }
}
```

When `includeSecrets` is true and a rule has a stored webhook token, export adds `webhook.token` as plaintext. When it is false, no token or token reference is exported.

## Android Bridge

Add two bridge methods to `MainActivity.NotiFlowBridge`:

- `exportRules(inputJson: String): string(JSON)`
- `importRules(inputJson: String): string(JSON)`

`exportRules` accepts:

```json
{
  "ruleIds": [1, 2, 3],
  "includeSecrets": false
}
```

It loads current rules, filters to the requested IDs, converts each rule into the normalized export shape, and optionally resolves `tokenRef` through `SecureStore`.

`importRules` accepts the full JSON file content. It validates `schemaVersion`, `app`, and `rules`, then converts each exported rule through the existing `buildRuleFromInput()` path with `existing = null`. Any incoming `id` is ignored so imports always create new rules.

## Error Handling

- Invalid JSON returns an error response.
- Unsupported `schemaVersion` returns an error response.
- Empty `rules` returns an error response.
- Missing selected rule IDs during export returns an error response.
- Import validation is all-or-nothing: if any rule is invalid, no rule should be inserted.
- Token lookup failure does not expose `tokenRef`; if a token cannot be found, the exported rule omits `webhook.token`.

## Web UI

Add rule transfer controls in the rules area or settings area using existing visual patterns.

- `룰 내보내기` opens an export sheet/dialog.
- `룰 가져오기` opens a hidden file input accepting `.json` and `application/json`.
- Export sheet shows rule checkboxes with rule name and package name.
- The sheet includes `전체 선택`, `전체 해제`, selected count, and `토큰 포함`.
- Browser mode disables import/export and shows the existing app-connection limitation behavior.
- On export success, the UI downloads a JSON file named like `notiflow-rules-YYYYMMDD-HHmmss.json`.
- On import success, the UI refreshes rules and shows `N개 룰을 가져왔습니다.`.

## Security

Webhook tokens remain excluded by default. If `토큰 포함` is enabled, the UI should warn that the exported JSON contains plaintext secrets. Importing a file with `webhook.token` stores that token through the existing SecureStore-backed create path.

## Testing

- Add Kotlin unit coverage for export payload shape.
- Add Kotlin unit coverage that import ignores incoming IDs and creates new rules.
- Add Kotlin unit coverage that import rejects invalid JSON or unsupported schema.
- Add a Web verification script checking that import/export controls, bridge calls, selected-rule UI, and token warning are present.
- Run Android unit tests and the new Web verification script before completion.
