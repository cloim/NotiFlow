# Rule JSON Import and Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build JSON rule export/import with selected-rule export, optional plaintext token inclusion, and append-only import.

**Architecture:** Add a focused Android `RuleTransfer` helper that converts domain `Rule` objects to/from the versioned export envelope. `MainActivity.NotiFlowBridge` exposes export/import methods and keeps file handling in the React Web UI. Import validates the full file before inserting new rules so existing rules are not overwritten.

**Tech Stack:** Kotlin, Android WebView JavaScript bridge, Room, kotlinx.serialization JSON objects, org.json, React 18, Vite, Node verification scripts.

---

## File Structure

- Create `app/src/main/java/com/vibe/notiflow/domain/transfer/RuleTransfer.kt`: Pure rule export/import JSON conversion and validation.
- Create `app/src/test/java/com/vibe/notiflow/RuleTransferTest.kt`: Unit tests for export shape, selected IDs, token handling, schema validation, and imported ID removal.
- Modify `app/src/main/java/com/vibe/notiflow/data/local/Dao.kt`: Add list insert for append-only import.
- Modify `app/src/main/java/com/vibe/notiflow/domain/repo/RuleRepository.kt`: Add `upsertRules()` wrapper for list insert.
- Modify `app/src/main/java/com/vibe/notiflow/MainActivity.kt`: Add bridge methods and reuse `RuleTransfer`.
- Modify `web/src/App.jsx`: Add export/import UI state, handlers, selected-rule sheet, hidden JSON file input, and bridge calls.
- Modify `web/src/styles.css`: Add styles for rule transfer controls and export sheet.
- Create `web/scripts/verify-rule-transfer.mjs`: Static contract verification for UI and bridge integration.
- Modify `web/package.json`: Add `test:rule-transfer` script.
- Modify `README.md`: Document the new bridge methods and JSON behavior.
- Modify `docs/superpowers/specs/2026-05-21-notiflow-architecture-guide.md`: Update bridge API list.

---

### Task 1: Android RuleTransfer Unit

**Files:**
- Create: `app/src/main/java/com/vibe/notiflow/domain/transfer/RuleTransfer.kt`
- Create: `app/src/test/java/com/vibe/notiflow/RuleTransferTest.kt`

- [ ] **Step 1: Write failing tests for export/import conversion**

Create `app/src/test/java/com/vibe/notiflow/RuleTransferTest.kt` with these tests:

```kotlin
package com.vibe.notiflow

import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.FilterSpec
import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.domain.transfer.RuleTransfer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTransferTest {
    @Test
    fun exportRules_exportsOnlySelectedRulesAndOmitsSecretsByDefault() {
        val exported = RuleTransfer.exportRules(
            rules = listOf(rule(id = 1), rule(id = 2)),
            selectedRuleIds = setOf(2),
            includeSecrets = false,
            tokenResolver = { "secret-token" },
            nowMillis = { 1710000000000L }
        )

        assertEquals(1, exported.getInt("schemaVersion"))
        assertEquals("NotiFlow", exported.getString("app"))
        assertEquals(1710000000000L, exported.getLong("exportedAt"))
        assertFalse(exported.getBoolean("includeSecrets"))

        val rules = exported.getJSONArray("rules")
        assertEquals(1, rules.length())
        val item = rules.getJSONObject(0)
        assertEquals(2L, item.getLong("id"))
        assertEquals("rule-2", item.getString("name"))
        assertEquals("com.example.app2", item.getString("packageName"))
        assertEquals("AND", item.getString("conditionOperator"))
        assertEquals("text.contains", item.getJSONArray("conditions").getJSONObject(0).getString("type"))
        assertEquals("otp", item.getJSONArray("conditions").getJSONObject(0).getString("value"))

        val webhook = item.getJSONObject("webhook")
        assertEquals("https://example.com/2", webhook.getString("url"))
        assertEquals("POST", webhook.getString("method"))
        assertFalse(webhook.has("token"))
        assertFalse(webhook.has("tokenRef"))
    }

    @Test
    fun exportRules_includesPlaintextTokenOnlyWhenRequested() {
        val exported = RuleTransfer.exportRules(
            rules = listOf(rule(id = 7, tokenRef = "webhook_7")),
            selectedRuleIds = setOf(7),
            includeSecrets = true,
            tokenResolver = { alias -> if (alias == "webhook_7") "plain-secret" else null },
            nowMillis = { 1710000000000L }
        )

        val webhook = exported.getJSONArray("rules").getJSONObject(0).getJSONObject("webhook")
        assertTrue(exported.getBoolean("includeSecrets"))
        assertEquals("plain-secret", webhook.getString("token"))
        assertFalse(webhook.has("tokenRef"))
    }

    @Test
    fun exportRules_rejectsMissingSelectedRuleIds() {
        val error = runCatching {
            RuleTransfer.exportRules(
                rules = listOf(rule(id = 1)),
                selectedRuleIds = setOf(1, 99),
                includeSecrets = false,
                tokenResolver = { null },
                nowMillis = { 1710000000000L }
            )
        }.exceptionOrNull()

        assertEquals("selected rules not found: 99", error?.message)
    }

    @Test
    fun importRuleInputs_removesIdsAndReturnsRuleInputs() {
        val raw = """
            {
              "schemaVersion": 1,
              "app": "NotiFlow",
              "exportedAt": 1710000000000,
              "includeSecrets": false,
              "rules": [
                {
                  "id": 42,
                  "name": "imported",
                  "packageName": "com.example.imported",
                  "conditionOperator": "AND",
                  "conditions": [{ "type": "text.contains", "value": "otp" }],
                  "conditionExpression": { "rows": [{ "type": "text.contains", "value": "otp", "openParen": 0, "closeParen": 0 }] },
                  "enabled": true,
                  "priority": 100,
                  "webhook": { "url": "https://example.com/import", "method": "POST" }
                }
              ]
            }
        """.trimIndent()

        val inputs = RuleTransfer.importRuleInputs(raw)

        assertEquals(1, inputs.size)
        assertFalse(inputs.single().has("id"))
        assertEquals("imported", inputs.single().getString("name"))
        assertEquals("com.example.imported", inputs.single().getString("packageName"))
    }

    @Test
    fun importRuleInputs_rejectsUnsupportedSchemaAndEmptyRules() {
        val badSchema = """{"schemaVersion":2,"app":"NotiFlow","rules":[{}]}"""
        val emptyRules = """{"schemaVersion":1,"app":"NotiFlow","rules":[]}"""

        assertEquals(
            "unsupported rule export schema: 2",
            runCatching { RuleTransfer.importRuleInputs(badSchema) }.exceptionOrNull()?.message
        )
        assertEquals(
            "rules must not be empty",
            runCatching { RuleTransfer.importRuleInputs(emptyRules) }.exceptionOrNull()?.message
        )
    }

    private fun rule(id: Long, tokenRef: String = "webhook_$id"): Rule {
        return Rule(
            id = id,
            name = "rule-$id",
            enabled = true,
            priority = 100,
            targetPackages = listOf("com.example.app$id"),
            filters = listOf(
                FilterSpec("package.equals", buildJsonObject { put("value", "com.example.app$id") }),
                FilterSpec("text.contains", buildJsonObject { put("value", "otp") })
            ),
            filterOperator = FilterOperator.AND,
            conditionExpression = ConditionExpression(
                rows = listOf(
                    ConditionExpressionRow(
                        type = "text.contains",
                        value = "otp",
                        operator = null,
                        openParen = 0,
                        closeParen = 0
                    )
                )
            ),
            actions = listOf(
                ActionSpec(
                    "webhook.post",
                    buildJsonObject {
                        put("url", "https://example.com/$id")
                        put("method", "POST")
                        put("tokenRef", tokenRef)
                    }
                )
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat testDevDebugUnitTest --tests "com.vibe.notiflow.RuleTransferTest"
```

Expected: FAIL because `com.vibe.notiflow.domain.transfer.RuleTransfer` does not exist.

- [ ] **Step 3: Implement RuleTransfer**

Create `app/src/main/java/com/vibe/notiflow/domain/transfer/RuleTransfer.kt`:

```kotlin
package com.vibe.notiflow.domain.transfer

import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.Rule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

object RuleTransfer {
    const val SCHEMA_VERSION = 1
    const val APP_NAME = "NotiFlow"

    fun exportRules(
        rules: List<Rule>,
        selectedRuleIds: Set<Long>,
        includeSecrets: Boolean,
        tokenResolver: (String) -> String?,
        nowMillis: () -> Long = { System.currentTimeMillis() }
    ): JSONObject {
        require(selectedRuleIds.isNotEmpty()) { "ruleIds must not be empty" }

        val byId = rules.associateBy { it.id }
        val missingIds = selectedRuleIds.filterNot { byId.containsKey(it) }.sorted()
        require(missingIds.isEmpty()) { "selected rules not found: ${missingIds.joinToString(", ")}" }

        val selectedRules = rules.filter { selectedRuleIds.contains(it.id) }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("app", APP_NAME)
            put("exportedAt", nowMillis())
            put("includeSecrets", includeSecrets)
            put(
                "rules",
                JSONArray().apply {
                    selectedRules.forEach { rule ->
                        put(ruleToExportJson(rule, includeSecrets, tokenResolver))
                    }
                }
            )
        }
    }

    fun importRuleInputs(inputJson: String): List<JSONObject> {
        val root = JSONObject(inputJson)
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion == SCHEMA_VERSION) { "unsupported rule export schema: $schemaVersion" }
        require(root.optString("app") == APP_NAME) { "app must be $APP_NAME" }

        val rules = root.optJSONArray("rules") ?: throw IllegalArgumentException("rules is required")
        require(rules.length() > 0) { "rules must not be empty" }

        return (0 until rules.length()).map { index ->
            val rule = rules.optJSONObject(index)
                ?: throw IllegalArgumentException("rules[$index] must be an object")
            JSONObject(rule.toString()).apply { remove("id") }
        }
    }

    private fun ruleToExportJson(
        rule: Rule,
        includeSecrets: Boolean,
        tokenResolver: (String) -> String?
    ): JSONObject {
        val expression = rule.conditionExpression ?: legacyExpression(rule)
        val conditions = expression.rows.map { it.type to it.value }
        val webhook = webhookJson(rule, includeSecrets, tokenResolver)

        return JSONObject().apply {
            put("id", rule.id)
            put("name", rule.name)
            put("packageName", rule.targetPackages.firstOrNull().orEmpty())
            put("conditionOperator", rule.filterOperator.name)
            put(
                "conditions",
                JSONArray().apply {
                    conditions.forEach { (type, value) ->
                        put(JSONObject().put("type", type).put("value", value))
                    }
                }
            )
            put("conditionExpression", JSONObject(Json.encodeToString(expression)))
            put("enabled", rule.enabled)
            put("priority", rule.priority)
            put("webhook", webhook)
        }
    }

    private fun legacyExpression(rule: Rule): ConditionExpression {
        val rows = rule.filters
            .filter { it.type != "package.equals" }
            .mapNotNull { filter ->
                filter.config["value"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    filter.type to value
                }
            }
            .mapIndexed { index, (type, value) ->
                ConditionExpressionRow(
                    type = type,
                    value = value,
                    operator = if (index < rule.filters.count { it.type != "package.equals" } - 1) {
                        rule.filterOperator
                    } else {
                        null
                    }
                )
            }
        return ConditionExpression(rows)
    }

    private fun webhookJson(
        rule: Rule,
        includeSecrets: Boolean,
        tokenResolver: (String) -> String?
    ): JSONObject {
        val config = rule.actions.firstOrNull { it.type == "webhook.post" }?.config
            ?: throw IllegalArgumentException("rule ${rule.id} has no webhook.post action")
        val source = JSONObject(config.toString())
        val webhook = JSONObject().apply {
            put("url", source.optString("url"))
            put("method", source.optString("method", "POST"))
            if (source.has("headers")) put("headers", source.getJSONObject("headers"))
            if (source.has("payloadTemplate")) put("payloadTemplate", source.getString("payloadTemplate"))
        }

        val tokenRef = config["tokenRef"]?.jsonPrimitive?.contentOrNull
        if (includeSecrets && !tokenRef.isNullOrBlank()) {
            tokenResolver(tokenRef)?.takeIf { it.isNotBlank() }?.let { token ->
                webhook.put("token", token)
            }
        }

        return webhook
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat testDevDebugUnitTest --tests "com.vibe.notiflow.RuleTransferTest"
```

Expected: PASS for all `RuleTransferTest` tests.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/vibe/notiflow/domain/transfer/RuleTransfer.kt app/src/test/java/com/vibe/notiflow/RuleTransferTest.kt
git commit -m "feat: add rule transfer JSON conversion"
```

---

### Task 2: Android Bridge Import and Export

**Files:**
- Modify: `app/src/main/java/com/vibe/notiflow/data/local/Dao.kt`
- Modify: `app/src/main/java/com/vibe/notiflow/domain/repo/RuleRepository.kt`
- Modify: `app/src/main/java/com/vibe/notiflow/MainActivity.kt`

- [ ] **Step 1: Add repository batch insert support**

In `app/src/main/java/com/vibe/notiflow/data/local/Dao.kt`, add this method under `upsert(entity: RuleEntity): Long`:

```kotlin
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RuleEntity>): List<Long>
```

In `app/src/main/java/com/vibe/notiflow/domain/repo/RuleRepository.kt`, add this method under `upsertRule`:

```kotlin
    suspend fun upsertRules(rules: List<Rule>): List<Long> = ruleDao.upsertAll(rules.map { it.toEntity() })
```

- [ ] **Step 2: Add bridge imports**

In `app/src/main/java/com/vibe/notiflow/MainActivity.kt`, add:

```kotlin
import com.vibe.notiflow.domain.transfer.RuleTransfer
```

- [ ] **Step 3: Add exportRules bridge method**

Inside `MainActivity.NotiFlowBridge`, insert this method near `listRules()`:

```kotlin
        @JavascriptInterface
        fun exportRules(inputJson: String): String {
            return runCatching {
                val input = JSONObject(inputJson)
                val idsArray = input.optJSONArray("ruleIds")
                    ?: throw IllegalArgumentException("ruleIds is required")
                val ruleIds = (0 until idsArray.length()).map { idsArray.getLong(it) }.toSet()
                val includeSecrets = input.optBoolean("includeSecrets", false)
                val rules = runBlocking { ServiceLocator.ruleRepository.getAllRules() }
                val exported = RuleTransfer.exportRules(
                    rules = rules,
                    selectedRuleIds = ruleIds,
                    includeSecrets = includeSecrets,
                    tokenResolver = { alias -> ServiceLocator.secureStore.getSecret(alias) }
                )
                okResponse(JSONObject().put("export", exported))
            }.getOrElse { errorResponse(it.message ?: "failed to export rules") }
        }
```

- [ ] **Step 4: Add importRules bridge method**

Inside `MainActivity.NotiFlowBridge`, insert this method near `createRule()`:

```kotlin
        @JavascriptInterface
        fun importRules(inputJson: String): String {
            return runCatching {
                val inputs = RuleTransfer.importRuleInputs(inputJson)
                val rules = inputs.mapIndexed { index, input ->
                    runCatching { buildRuleFromInput(input, existing = null) }
                        .getOrElse { throw IllegalArgumentException("rules[$index]: ${it.message}") }
                }
                val ids = runBlocking { ServiceLocator.ruleRepository.upsertRules(rules) }
                okResponse(
                    JSONObject().apply {
                        put("imported", ids.size)
                        put("ruleIds", JSONArray(ids))
                    }
                )
            }.getOrElse { errorResponse(it.message ?: "failed to import rules") }
        }
```

- [ ] **Step 5: Run Android tests**

Run:

```powershell
.\gradlew.bat testDevDebugUnitTest
```

Expected: PASS for the full dev debug unit test suite.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/vibe/notiflow/data/local/Dao.kt app/src/main/java/com/vibe/notiflow/domain/repo/RuleRepository.kt app/src/main/java/com/vibe/notiflow/MainActivity.kt
git commit -m "feat: expose rule import export bridge"
```

---

### Task 3: Web Rule Transfer UI

**Files:**
- Modify: `web/src/App.jsx`
- Modify: `web/src/styles.css`

- [ ] **Step 1: Add export sheet component**

In `web/src/App.jsx`, add this component before `LogItem`:

```jsx
function RuleExportSheet({ open, rules, selectedIds, includeSecrets, busy, onToggleRule, onSelectAll, onClearAll, onToggleSecrets, onExport, onClose }) {
  if (!open) return null;
  const selectedCount = selectedIds.size;
  return (
    <>
      <div className="sheet-backdrop open" onClick={onClose} />
      <div className="rule-export-sheet open" role="dialog" aria-modal="true">
        <div className="sheet-head">
          <div>
            <div className="sheet-title">룰 내보내기</div>
            <div className="sheet-sub">선택된 {selectedCount}개 룰을 JSON 파일로 저장합니다.</div>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="닫기">×</button>
        </div>

        <div className="rule-export-toolbar">
          <button className="btn btn-ghost" onClick={onSelectAll} disabled={busy || rules.length === 0}>전체 선택</button>
          <button className="btn btn-ghost" onClick={onClearAll} disabled={busy || rules.length === 0}>전체 해제</button>
        </div>

        <div className="rule-export-list">
          {rules.map((rule) => {
            const id = Number(rule.id);
            const checked = selectedIds.has(id);
            return (
              <label key={rule.id} className="rule-export-item">
                <input type="checkbox" checked={checked} onChange={() => onToggleRule(id)} disabled={busy} />
                <span className="rule-export-copy">
                  <strong>{rule.name || `규칙 #${rule.id}`}</strong>
                  <span>{rule.targetPackages?.[0] ?? "패키지 없음"}</span>
                </span>
              </label>
            );
          })}
        </div>

        <label className="rule-export-secret">
          <input type="checkbox" checked={includeSecrets} onChange={(e) => onToggleSecrets(e.target.checked)} disabled={busy} />
          <span>
            <strong>토큰 포함</strong>
            <small>켜면 JSON 파일에 webhook 토큰이 평문으로 저장됩니다.</small>
          </span>
        </label>

        <div className="sheet-foot">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>취소</button>
          <button className="btn btn-primary" onClick={onExport} disabled={busy || selectedCount === 0}>JSON 내보내기</button>
        </div>
      </div>
    </>
  );
}
```

- [ ] **Step 2: Add App state and refs**

Inside `App`, near other `useState` calls, add:

```jsx
  const [isExportSheetOpen, setIsExportSheetOpen] = useState(false);
  const [selectedExportRuleIds, setSelectedExportRuleIds] = useState(() => new Set());
  const [includeExportSecrets, setIncludeExportSecrets] = useState(false);
  const [ruleTransferBusy, setRuleTransferBusy] = useState(false);
  const importFileInputRef = useRef(null);
```

- [ ] **Step 3: Add file download helper**

In `App`, near other helper callbacks, add:

```jsx
  const downloadJsonFile = useCallback((payload) => {
    const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z").replace("T", "-");
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `notiflow-rules-${stamp}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }, []);
```

- [ ] **Step 4: Add export callbacks**

In `App`, near rule action callbacks, add:

```jsx
  const openRuleExport = useCallback(() => {
    if (!isNative || !native?.exportRules) {
      showToast("앱 연결 기능을 사용할 수 없습니다.", "err");
      return;
    }
    setSelectedExportRuleIds(new Set(rules.map((rule) => Number(rule.id))));
    setIncludeExportSecrets(false);
    setIsExportSheetOpen(true);
  }, [isNative, native, rules, showToast]);

  const toggleExportRule = useCallback((ruleId) => {
    setSelectedExportRuleIds((prev) => {
      const next = new Set(prev);
      if (next.has(ruleId)) next.delete(ruleId);
      else next.add(ruleId);
      return next;
    });
  }, []);

  const exportSelectedRules = useCallback(() => {
    if (!native?.exportRules) return;
    const ruleIds = Array.from(selectedExportRuleIds);
    if (!ruleIds.length) {
      showToast("내보낼 룰을 선택하세요.", "err");
      return;
    }
    setRuleTransferBusy(true);
    try {
      const r = parseBridge(native.exportRules(JSON.stringify({ ruleIds, includeSecrets: includeExportSecrets })));
      if (!r?.ok) {
        showToast(r?.error ?? "룰을 내보내지 못했습니다.", "err");
        return;
      }
      downloadJsonFile(r.data?.export);
      setIsExportSheetOpen(false);
      showToast(`${ruleIds.length}개 룰을 내보냈습니다.`);
    } finally {
      setRuleTransferBusy(false);
    }
  }, [native, selectedExportRuleIds, includeExportSecrets, downloadJsonFile, showToast]);
```

- [ ] **Step 5: Add import callbacks**

In `App`, near `exportSelectedRules`, add:

```jsx
  const openRuleImport = useCallback(() => {
    if (!isNative || !native?.importRules) {
      showToast("앱 연결 기능을 사용할 수 없습니다.", "err");
      return;
    }
    importFileInputRef.current?.click();
  }, [isNative, native, showToast]);

  const importRulesFromFile = useCallback(async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !native?.importRules) return;

    setRuleTransferBusy(true);
    try {
      const text = await file.text();
      const r = parseBridge(native.importRules(text));
      if (!r?.ok) {
        showToast(r?.error ?? "룰을 가져오지 못했습니다.", "err");
        return;
      }
      const imported = Number(r.data?.imported ?? 0);
      showToast(`${imported}개 룰을 가져왔습니다.`);
      loadRules();
    } finally {
      setRuleTransferBusy(false);
    }
  }, [native, showToast, loadRules]);
```

- [ ] **Step 6: Add buttons in rules tab**

Inside the rules tab content, before the empty-state/rules list block, add:

```jsx
          <div className="rule-transfer-card">
            <div>
              <div className="rule-transfer-title">룰 백업</div>
              <div className="rule-transfer-desc">선택한 룰을 JSON으로 내보내거나 다른 기기에서 가져옵니다.</div>
            </div>
            <div className="rule-transfer-actions">
              <button className="btn btn-ghost" onClick={openRuleImport} disabled={!isNative || ruleTransferBusy}>룰 가져오기</button>
              <button className="btn btn-primary" onClick={openRuleExport} disabled={!isNative || ruleTransferBusy || rules.length === 0}>룰 내보내기</button>
            </div>
            <input ref={importFileInputRef} type="file" accept=".json,application/json" hidden onChange={importRulesFromFile} />
          </div>
```

- [ ] **Step 7: Render export sheet**

Near other dialogs at the bottom of `App`, before `PackageLoadingDialog`, add:

```jsx
      <RuleExportSheet
        open={isExportSheetOpen}
        rules={rules}
        selectedIds={selectedExportRuleIds}
        includeSecrets={includeExportSecrets}
        busy={ruleTransferBusy}
        onToggleRule={toggleExportRule}
        onSelectAll={() => setSelectedExportRuleIds(new Set(rules.map((rule) => Number(rule.id))))}
        onClearAll={() => setSelectedExportRuleIds(new Set())}
        onToggleSecrets={setIncludeExportSecrets}
        onExport={exportSelectedRules}
        onClose={() => setIsExportSheetOpen(false)}
      />
```

- [ ] **Step 8: Add CSS**

In `web/src/styles.css`, add these styles near existing settings/card styles:

```css
.rule-transfer-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  background: linear-gradient(135deg, var(--card), var(--card-hi));
  border: 1px solid var(--border-hi);
  border-radius: var(--r);
}
.rule-transfer-title { font-size: 15px; font-weight: 800; color: var(--t1); }
.rule-transfer-desc { margin-top: 2px; font-size: 12.5px; color: var(--t3); }
.rule-transfer-actions { display: flex; gap: 8px; }
.rule-transfer-actions .btn { flex: 1; }

.rule-export-sheet {
  position: fixed;
  left: 50%;
  bottom: 0;
  width: min(480px, 100%);
  max-height: var(--sheet-max);
  transform: translate(-50%, 105%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 16px calc(var(--safe-b) + 16px);
  background: var(--surface);
  border: 1px solid var(--border-hi);
  border-radius: 22px 22px 0 0;
  box-shadow: 0 -18px 60px rgba(0,0,0,.38);
  z-index: 260;
  transition: transform .22s ease;
}
.rule-export-sheet.open { transform: translate(-50%, 0); }
.rule-export-toolbar { display: flex; gap: 8px; }
.rule-export-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 38dvh;
  overflow: auto;
}
.rule-export-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--card);
}
.rule-export-copy { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.rule-export-copy strong { color: var(--t1); font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rule-export-copy span { color: var(--t3); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rule-export-secret {
  display: flex;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--amber-dim);
  border-radius: var(--r-sm);
  background: var(--amber-dim);
}
.rule-export-secret span { display: flex; flex-direction: column; gap: 2px; }
.rule-export-secret strong { color: var(--t1); font-size: 13px; }
.rule-export-secret small { color: var(--t2); font-size: 12px; }
```

- [ ] **Step 9: Build Web UI**

Run:

```powershell
npm run build
```

Workdir: `web`

Expected: Vite build succeeds and writes `web/dist`.

- [ ] **Step 10: Commit**

```powershell
git add web/src/App.jsx web/src/styles.css
git commit -m "feat: add selected rule transfer UI"
```

---

### Task 4: Contract Verification and Documentation

**Files:**
- Create: `web/scripts/verify-rule-transfer.mjs`
- Modify: `web/package.json`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-05-21-notiflow-architecture-guide.md`

- [ ] **Step 1: Add Web contract verification script**

Create `web/scripts/verify-rule-transfer.mjs`:

```js
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const app = read("web/src/App.jsx");
const css = read("web/src/styles.css");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const transfer = read("app/src/main/java/com/vibe/notiflow/domain/transfer/RuleTransfer.kt");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "exportRules(inputJson: String)",
  "importRules(inputJson: String)",
  "RuleTransfer.exportRules",
  "RuleTransfer.importRuleInputs",
]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity must include ${text}`);
}

for (const text of [
  "schemaVersion",
  "includeSecrets",
  "selectedRuleIds",
  "tokenResolver",
]) {
  if (!transfer.includes(text)) failures.push(`RuleTransfer must include ${text}`);
}

for (const text of [
  "RuleExportSheet",
  "룰 내보내기",
  "룰 가져오기",
  "전체 선택",
  "전체 해제",
  "토큰 포함",
  "selectedExportRuleIds",
  "native.exportRules",
  "native.importRules",
  "notiflow-rules-",
]) {
  if (!app.includes(text)) failures.push(`App must include ${text}`);
}

for (const text of [
  ".rule-transfer-card",
  ".rule-export-sheet",
  ".rule-export-item",
  ".rule-export-secret",
]) {
  if (!css.includes(text)) failures.push(`styles.css must include ${text}`);
}

if (pkg.scripts["test:rule-transfer"] !== "node scripts/verify-rule-transfer.mjs") {
  failures.push("package.json must expose test:rule-transfer");
}

if (failures.length) {
  console.error("Rule transfer contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Rule transfer contract passed");
```

- [ ] **Step 2: Add npm script**

In `web/package.json`, add this script entry after `test:pc-settings-server`:

```json
    "test:rule-transfer": "node scripts/verify-rule-transfer.mjs",
```

- [ ] **Step 3: Update README bridge list**

In `README.md`, add these bridge methods under `setRuleEnabled`:

```markdown
- `exportRules(inputJson: string): string(JSON)`
- `importRules(inputJson: string): string(JSON)`
```

Then add this section after the `updateRule` token behavior bullets:

```markdown
`exportRules` 입력 예시:
```json
{
  "ruleIds": [1, 2],
  "includeSecrets": false
}
```

반환 데이터의 `data.export`는 `schemaVersion`, `app`, `exportedAt`, `includeSecrets`, `rules`를 포함하는 JSON 백업 객체입니다. `includeSecrets`가 `true`일 때만 webhook 토큰이 `webhook.token` 평문으로 포함됩니다.

`importRules`는 내보낸 JSON 문자열 전체를 입력받아 기존 룰을 유지한 채 새 룰로 추가합니다. 파일 안의 `id`는 무시됩니다.
```

- [ ] **Step 4: Update architecture guide bridge list**

In `docs/superpowers/specs/2026-05-21-notiflow-architecture-guide.md`, add these rows to the bridge API table after `setRuleEnabled`:

```markdown
| `exportRules(inputJson)` | `{ data: { export } }` |
| `importRules(inputJson)` | `{ data: { imported, ruleIds } }` |
```

- [ ] **Step 5: Run verification scripts**

Run:

```powershell
npm run test:rule-transfer
```

Workdir: `web`

Expected: `Rule transfer contract passed`.

Run:

```powershell
npm run build
```

Workdir: `web`

Expected: Vite build succeeds.

Run:

```powershell
.\gradlew.bat testDevDebugUnitTest
```

Workdir: repository root.

Expected: Android unit tests pass.

- [ ] **Step 6: Commit**

```powershell
git add web/scripts/verify-rule-transfer.mjs web/package.json README.md docs/superpowers/specs/2026-05-21-notiflow-architecture-guide.md
git commit -m "docs: document rule transfer workflow"
```

---

### Task 5: Android Asset Sync and Final Verification

**Files:**
- Modify: `app/src/main/assets/web/index.html`
- Modify: `app/src/main/assets/web/assets/*`

- [ ] **Step 1: Build and sync Web assets into Android**

Run:

```powershell
npm run build:android
```

Workdir: `web`

Expected: Vite build succeeds and `app/src/main/assets/web` is updated.

- [ ] **Step 2: Run final Web checks**

Run:

```powershell
npm run test:rule-transfer
```

Workdir: `web`

Expected: `Rule transfer contract passed`.

Run:

```powershell
npm run build
```

Workdir: `web`

Expected: Vite build succeeds.

- [ ] **Step 3: Run final Android tests**

Run:

```powershell
.\gradlew.bat testDevDebugUnitTest
```

Workdir: repository root.

Expected: Android unit tests pass.

- [ ] **Step 4: Inspect changed files**

Run:

```powershell
git status --short
```

Expected: only rule transfer source, tests, docs, package script, and synced web assets are changed.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/assets/web web/src/App.jsx web/src/styles.css app/src/main/java/com/vibe/notiflow app/src/test/java/com/vibe/notiflow web/scripts/verify-rule-transfer.mjs web/package.json README.md docs/superpowers/specs/2026-05-21-notiflow-architecture-guide.md
git commit -m "feat: add rule JSON import export"
```

---

## Self-Review

- Spec coverage: selected-rule export is in Task 3; optional token inclusion is in Tasks 1 and 3; append-only import with ID removal is in Tasks 1 and 2; JSON envelope is in Task 1; docs and verification are in Task 4; Android asset sync is in Task 5.
- Placeholder scan: the plan contains concrete file paths, method signatures, commands, expected outputs, and code blocks for new units.
- Type consistency: bridge methods are `exportRules(inputJson: String)` and `importRules(inputJson: String)`; Web calls use `native.exportRules(...)` and `native.importRules(...)`; JSON keys are consistently `schemaVersion`, `app`, `exportedAt`, `includeSecrets`, `rules`, `ruleIds`, and `selectedRuleIds` only inside Kotlin helper arguments.
