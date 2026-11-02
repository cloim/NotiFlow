package com.vibe.notiflow

import com.vibe.notiflow.data.local.SecureStore
import com.vibe.notiflow.remote.RemoteAccessCode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class RemoteAccessCodeTest {
    private val secrets = mutableMapOf<String, String>()
    private val store = mockk<SecureStore>().apply {
        every { getSecret(any()) } answers { secrets[firstArg()] }
        every { putSecret(any(), any()) } answers { secrets[firstArg()] = secondArg(); Unit }
        every { removeSecret(any()) } answers { secrets.remove(firstArg<String>()); Unit }
    }
    private val codes = RemoteAccessCode(store)

    @Test fun customCodePersistsAcrossInstancesAndIsScopedToTheAccount() {
        codes.save("user-a", "custom-code-2026")
        assertEquals("custom-code-2026", RemoteAccessCode(store).get("user-a"))
        assertNull(codes.get("user-b"))
        codes.save("user-b", "another-code-2026")
        assertEquals("custom-code-2026", codes.get("user-a"))
        assertEquals("another-code-2026", codes.get("user-b"))
    }

    @Test fun clearingACodeReturnsToAutomaticModeWithoutChangingAnotherAccount() {
        codes.save("user-a", "custom-code-2026")
        codes.save("user-b", "another-code-2026")
        codes.save("user-a", "")
        assertNull(codes.get("user-a"))
        assertEquals("another-code-2026", codes.get("user-b"))
    }

    @Test fun invalidCodesAreRejectedWithoutReplacingTheSavedCode() {
        codes.save("user-a", "custom-code-2026")
        for (value in listOf("1234567", "has a space", "한글접속코드입니다", "x".repeat(129), "line\nbreak")) {
            assertTrue(runCatching { codes.save("user-a", value) }.isFailure)
            assertEquals("custom-code-2026", codes.get("user-a"))
        }
    }
}
