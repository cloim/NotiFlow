package com.vibe.notiflow

import com.vibe.notiflow.domain.engine.ConditionExpressionEvaluator
import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionExpressionEvaluatorTest {
    @Test
    fun expression_group_A_AND_B_then_OR_C() {
        val expression = ConditionExpression(
            rows = listOf(
                row("A", operator = FilterOperator.AND, openParen = 1),
                row("B", operator = FilterOperator.OR, closeParen = 1),
                row("C")
            )
        )

        val result = ConditionExpressionEvaluator.evaluate(expression) { condition ->
            condition.value in setOf("C")
        }

        assertTrue(result is ConditionExpressionEvaluator.EvaluationResult.Valid)
        assertTrue((result as ConditionExpressionEvaluator.EvaluationResult.Valid).matched)
    }

    @Test
    fun expression_A_AND_group_B_OR_C() {
        val expression = ConditionExpression(
            rows = listOf(
                row("A", operator = FilterOperator.AND),
                row("B", operator = FilterOperator.OR, openParen = 1),
                row("C", closeParen = 1)
            )
        )

        val result = ConditionExpressionEvaluator.evaluate(expression) { condition ->
            condition.value in setOf("C")
        }

        assertTrue(result is ConditionExpressionEvaluator.EvaluationResult.Valid)
        assertFalse((result as ConditionExpressionEvaluator.EvaluationResult.Valid).matched)
    }

    @Test
    fun expression_nested_double_group() {
        val expression = ConditionExpression(
            rows = listOf(
                row("A", operator = FilterOperator.AND, openParen = 2),
                row("B", operator = FilterOperator.OR, closeParen = 1),
                row("C", operator = FilterOperator.AND, openParen = 1),
                row("D", closeParen = 2)
            )
        )

        val result = ConditionExpressionEvaluator.evaluate(expression) { condition ->
            condition.value in setOf("C", "D")
        }

        assertTrue(result is ConditionExpressionEvaluator.EvaluationResult.Valid)
        assertTrue((result as ConditionExpressionEvaluator.EvaluationResult.Valid).matched)
    }

    @Test
    fun invalid_parentheses_are_guarded() {
        val expression = ConditionExpression(
            rows = listOf(
                row("A", operator = FilterOperator.AND, closeParen = 1),
                row("B")
            )
        )

        val result = ConditionExpressionEvaluator.evaluate(expression) { true }
        assertTrue(result is ConditionExpressionEvaluator.EvaluationResult.Invalid)
    }

    private fun row(
        value: String,
        operator: FilterOperator? = null,
        openParen: Int = 0,
        closeParen: Int = 0
    ): ConditionExpressionRow {
        return ConditionExpressionRow(
            type = "text.contains",
            value = value,
            operator = operator,
            openParen = openParen,
            closeParen = closeParen
        )
    }
}
