package com.monticker.api.common.account

/**
 * 회원 탈퇴(users.deleted_at을 채우는 소프트 삭제). 탈퇴 트랜잭션 안에서 동기로 발행한다 — 리스너가 지우는 사용자 데이터가
 * 탈퇴와 함께 커밋되거나 함께 롤백된다. 사용자 행이 남으므로 ON DELETE CASCADE로는 지워지지 않는 데이터를 여기서 지운다.
 */
data class UserAccountDeletedEvent(val userId: Long)
