package com.vibe.notiflow.domain.engine

data class ProcessingSummary(
    val successfulActions: Int = 0,
    val failedActions: Int = 0,
    val duplicate: Boolean = false
) {
    fun shareMessage(): String = when {
        successfulActions > 0 && failedActions > 0 -> "웹훅 일부 실패: 성공 ${successfulActions}건, 실패 ${failedActions}건"
        failedActions > 0 -> "웹훅 실행 실패: 실행 기록을 확인해 주세요"
        successfulActions > 0 -> "웹훅 실행 성공"
        else -> "공유 실패: 실행할 웹훅 규칙이 없어요"
    }
}
