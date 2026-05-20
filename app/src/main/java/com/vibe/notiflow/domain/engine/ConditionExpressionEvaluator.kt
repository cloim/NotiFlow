package com.vibe.notiflow.domain.engine

import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator

object ConditionExpressionEvaluator {
    sealed class EvaluationResult {
        data class Valid(val matched: Boolean) : EvaluationResult()
        data class Invalid(val reason: String) : EvaluationResult()
    }

    private sealed interface InfixToken {
        data class Operand(val rowIndex: Int) : InfixToken
        data class Operator(val value: FilterOperator) : InfixToken
        data object LeftParen : InfixToken
        data object RightParen : InfixToken
    }

    private sealed interface RpnToken {
        data class Operand(val row: ConditionExpressionRow) : RpnToken
        data class Operator(val value: FilterOperator) : RpnToken
    }

    private sealed class ParseResult {
        data class Success(val rpn: List<RpnToken>) : ParseResult()
        data class Failure(val reason: String) : ParseResult()
    }

    fun validationError(expression: ConditionExpression): String? {
        return when (val parse = parse(expression)) {
            is ParseResult.Success -> null
            is ParseResult.Failure -> parse.reason
        }
    }

    fun evaluate(
        expression: ConditionExpression,
        matchesRow: (ConditionExpressionRow) -> Boolean
    ): EvaluationResult {
        val parse = parse(expression)
        if (parse is ParseResult.Failure) {
            return EvaluationResult.Invalid(parse.reason)
        }

        val rpn = (parse as ParseResult.Success).rpn
        val stack = ArrayDeque<Boolean>()
        for (token in rpn) {
            when (token) {
                is RpnToken.Operand -> stack.addLast(matchesRow(token.row))
                is RpnToken.Operator -> {
                    if (stack.size < 2) {
                        return EvaluationResult.Invalid("operator missing operand")
                    }
                    val right = stack.removeLast()
                    val left = stack.removeLast()
                    val combined = when (token.value) {
                        FilterOperator.AND -> left && right
                        FilterOperator.OR -> left || right
                    }
                    stack.addLast(combined)
                }
            }
        }

        if (stack.size != 1) {
            return EvaluationResult.Invalid("expression did not reduce to one result")
        }
        return EvaluationResult.Valid(stack.last())
    }

    private fun parse(expression: ConditionExpression): ParseResult {
        val normalizedRows = expression.rows.mapIndexed { index, row ->
            val type = row.type.trim()
            val value = row.value.trim()
            if (type.isBlank() || value.isBlank()) {
                return ParseResult.Failure("row ${index + 1} requires non-empty type/value")
            }
            if (row.openParen < 0 || row.closeParen < 0) {
                return ParseResult.Failure("row ${index + 1} has negative parenthesis count")
            }
            if (index < expression.rows.lastIndex && row.operator == null) {
                return ParseResult.Failure("row ${index + 1} requires an operator")
            }
            row.copy(type = type, value = value)
        }
        if (normalizedRows.isEmpty()) {
            return ParseResult.Failure("conditionExpression.rows must not be empty")
        }

        val infixTokens = mutableListOf<InfixToken>()
        normalizedRows.forEachIndexed { index, row ->
            repeat(row.openParen) { infixTokens += InfixToken.LeftParen }
            infixTokens += InfixToken.Operand(index)
            repeat(row.closeParen) { infixTokens += InfixToken.RightParen }
            if (index < normalizedRows.lastIndex) {
                infixTokens += InfixToken.Operator(row.operator!!)
            }
        }
        return toRpn(infixTokens, normalizedRows)
    }

    private fun toRpn(
        infixTokens: List<InfixToken>,
        rows: List<ConditionExpressionRow>
    ): ParseResult {
        val output = mutableListOf<RpnToken>()
        val stack = ArrayDeque<InfixToken>()

        for (token in infixTokens) {
            when (token) {
                is InfixToken.Operand -> output += RpnToken.Operand(rows[token.rowIndex])
                is InfixToken.Operator -> {
                    while (stack.isNotEmpty()) {
                        val top = stack.last()
                        if (top is InfixToken.Operator && precedence(top.value) >= precedence(token.value)) {
                            stack.removeLast()
                            output += RpnToken.Operator(top.value)
                        } else {
                            break
                        }
                    }
                    stack.addLast(token)
                }

                InfixToken.LeftParen -> stack.addLast(token)

                InfixToken.RightParen -> {
                    var matchedLeftParen = false
                    while (stack.isNotEmpty()) {
                        when (val top = stack.removeLast()) {
                            InfixToken.LeftParen -> {
                                matchedLeftParen = true
                                break
                            }

                            is InfixToken.Operator -> output += RpnToken.Operator(top.value)
                            else -> Unit
                        }
                    }
                    if (!matchedLeftParen) {
                        return ParseResult.Failure("unmatched closing parenthesis")
                    }
                }
            }
        }

        while (stack.isNotEmpty()) {
            when (val top = stack.removeLast()) {
                InfixToken.LeftParen -> return ParseResult.Failure("unmatched opening parenthesis")
                is InfixToken.Operator -> output += RpnToken.Operator(top.value)
                else -> Unit
            }
        }

        return ParseResult.Success(output)
    }

    private fun precedence(operator: FilterOperator): Int {
        return when (operator) {
            FilterOperator.AND -> 2
            FilterOperator.OR -> 1
        }
    }
}
