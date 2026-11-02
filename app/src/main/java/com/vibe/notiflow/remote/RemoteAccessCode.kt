package com.vibe.notiflow.remote

import com.vibe.notiflow.data.local.SecureStore

/** Custom codes belong to the signed-in user and stay in encrypted preferences. */
class RemoteAccessCode(private val store: SecureStore) {
    fun get(uid: String): String? = store.getSecret("remote_editor_code_$uid")

    fun save(uid: String, value: String) {
        require(value.isEmpty() || (value.length in 8..128 && value.all { it.code in 33..126 })) {
            "접속 코드는 공백 없이 영문, 숫자와 기호 8~128자로 입력해 주세요"
        }
        if (value.isEmpty()) store.removeSecret("remote_editor_code_$uid")
        else store.putSecret("remote_editor_code_$uid", value)
    }
}
