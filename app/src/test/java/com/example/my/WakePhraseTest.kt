package com.example.my

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test
    fun recognizesCommonWakePhraseVariants() {
        assertTrue(containsNurAiWakePhrase("Привет, Нурай!"))
        assertTrue(containsNurAiWakePhrase("привет нур ай"))
        assertTrue(containsNurAiWakePhrase("Нурай, привет"))
        assertTrue(containsNurAiWakePhrase("Эй, привет Нурай пожалуйста"))
    }

    @Test
    fun ignoresUnrelatedSpeech() {
        assertFalse(containsNurAiWakePhrase("Привет, как дела?"))
        assertFalse(containsNurAiWakePhrase("Расскажи про нотариуса"))
        assertFalse(containsNurAiWakePhrase(""))
    }
}
