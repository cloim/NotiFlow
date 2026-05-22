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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTransferTest {
    @Test
    fun exportRules_exportsOnlySelectedRulesAndOmitsSecretsByDefault() {
        val rules = listOf(rule(id = 2), rule(id = 1, packageName = "com.other"))

        val export = RuleTransfer.exportRules(
            rules = rules,
            selectedRuleIds = setOf(2),
            includeSecrets = false,
            tokenResolver = { "secret-token" },
            nowMillis = { 1234L }
        )

        assertEquals(1, export.getInt("schemaVersion"))
        assertEquals("NotiFlow", export.getString("app"))
        assertEquals(1234L, export.getLong("exportedAt"))
        assertFalse(export.getBoolean("includeSecrets"))

        val exportedRules = export.getJSONArray("rules")
        assertEquals(1, exportedRules.length())
        val exportedRule = exportedRules.getJSONObject(0)
        assertEquals(2L, exportedRule.getLong("id"))
        assertEquals("rule-2", exportedRule.getString("name"))
        assertEquals("com.test", exportedRule.getString("packageName"))
        assertEquals("AND", exportedRule.getString("conditionOperator"))
        assertTrue(exportedRule.getBoolean("enabled"))
        assertEquals(100, exportedRule.getInt("priority"))

        val conditions = exportedRule.getJSONArray("conditions")
        assertEquals(2, conditions.length())
        assertEquals("title.contains", conditions.getJSONObject(0).getString("type"))
        assertEquals("alert", conditions.getJSONObject(0).getString("value"))
        assertEquals("AND", conditions.getJSONObject(0).getString("operator"))
        assertEquals("text.contains", conditions.getJSONObject(1).getString("type"))
        assertFalse(conditions.getJSONObject(1).has("operator"))

        val expressionRows = exportedRule.getJSONObject("conditionExpression").getJSONArray("rows")
        assertEquals(2, expressionRows.length())
        assertEquals("title.contains", expressionRows.getJSONObject(0).getString("type"))

        val webhook = exportedRule.getJSONObject("webhook")
        assertEquals("https://example.com/webhook", webhook.getString("url"))
        assertEquals("POST", webhook.getString("method"))
        assertEquals("application/json", webhook.getJSONObject("headers").getString("Content-Type"))
        assertEquals("{\"ok\":true}", webhook.getString("payloadTemplate"))
        assertFalse(webhook.has("tokenRef"))
        assertFalse(webhook.has("token"))
    }

    @Test
    fun exportRules_includesPlaintextTokenOnlyWhenRequested() {
        val export = RuleTransfer.exportRules(
            rules = listOf(rule(id = 1)),
            selectedRuleIds = setOf(1),
            includeSecrets = true,
            tokenResolver = { tokenRef -> if (tokenRef == "token-ref-1") "plain-token" else "" },
            nowMillis = { 1L }
        )

        val webhook = export.getJSONArray("rules").getJSONObject(0).getJSONObject("webhook")
        assertEquals("plain-token", webhook.getString("token"))
        assertFalse(webhook.has("tokenRef"))
    }

    @Test
    fun exportRules_rejectsMissingSelectedRuleIds() {
        assertEquals(
            "ruleIds must not be empty",
            assertThrows(IllegalArgumentException::class.java) {
                RuleTransfer.exportRules(
                    rules = listOf(rule(id = 1)),
                    selectedRuleIds = emptySet(),
                    includeSecrets = false,
                    tokenResolver = { null },
                    nowMillis = { 1L }
                )
            }.message
        )

        assertEquals(
            "selected rules not found: 2, 3",
            assertThrows(IllegalArgumentException::class.java) {
                RuleTransfer.exportRules(
                    rules = listOf(rule(id = 1)),
                    selectedRuleIds = setOf(3, 2, 1),
                    includeSecrets = false,
                    tokenResolver = { null },
                    nowMillis = { 1L }
                )
            }.message
        )
    }

    @Test
    fun importRuleInputs_removesIdsAndReturnsRuleInputs() {
        val input = JSONObject()
            .put("schemaVersion", 1)
            .put("app", "NotiFlow")
            .put("rules", RuleTransfer.exportRules(
                rules = listOf(rule(id = 9)),
                selectedRuleIds = setOf(9),
                includeSecrets = false,
                tokenResolver = { null },
                nowMillis = { 1L }
            ).getJSONArray("rules"))

        val imports = RuleTransfer.importRuleInputs(input)

        assertEquals(1, imports.size)
        assertFalse(imports.single().has("id"))
        assertEquals("rule-9", imports.single().getString("name"))
        assertEquals("com.test", imports.single().getString("packageName"))
    }

    @Test
    fun importRuleInputs_rejectsUnsupportedSchemaAndEmptyRules() {
        assertEquals(
            "unsupported rule export schema: 2",
            assertThrows(IllegalArgumentException::class.java) {
                RuleTransfer.importRuleInputs(
                    JSONObject().put("schemaVersion", 2).put("app", "NotiFlow").put("rules", emptyList<Any>())
                )
            }.message
        )

        assertEquals(
            "rules must not be empty",
            assertThrows(IllegalArgumentException::class.java) {
                RuleTransfer.importRuleInputs(
                    JSONObject().put("schemaVersion", 1).put("app", "NotiFlow").put("rules", emptyList<Any>())
                )
            }.message
        )
    }

    private fun rule(id: Long, packageName: String = "com.test"): Rule {
        return Rule(
            id = id,
            name = "rule-$id",
            enabled = true,
            priority = 100,
            targetPackages = listOf(packageName),
            filters = listOf(
                FilterSpec("package.equals", buildJsonObject { put("value", packageName) }),
                FilterSpec("title.contains", buildJsonObject { put("value", "alert") }),
                FilterSpec("text.contains", buildJsonObject { put("value", "otp") })
            ),
            filterOperator = FilterOperator.AND,
            conditionExpression = ConditionExpression(
                rows = listOf(
                    ConditionExpressionRow("title.contains", "alert", FilterOperator.AND),
                    ConditionExpressionRow("text.contains", "otp")
                )
            ),
            actions = listOf(
                ActionSpec(
                    "webhook.post",
                    buildJsonObject {
                        put("url", "https://example.com/webhook")
                        put("method", "POST")
                        put("payloadTemplate", "{\"ok\":true}")
                        put("headers", buildJsonObject { put("Content-Type", "application/json") })
                        put("tokenRef", "token-ref-1")
                    }
                )
            )
        )
    }
}
