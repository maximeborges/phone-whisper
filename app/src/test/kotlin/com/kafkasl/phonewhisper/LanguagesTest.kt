package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class LanguagesTest {
    private fun sup(name: String) = Languages.supported(name, ModelArch.fromRepoName(name))

    @Test fun `multilingual whisper supports anything incl czech`() {
        assertNull(sup("sherpa-onnx-whisper-small"))
    }

    @Test fun `english whisper only en`() {
        assertEquals(setOf("en"), sup("sherpa-onnx-whisper-tiny.en"))
    }

    @Test fun `parakeet v3 supports czech`() {
        assertTrue("cs" in sup("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")!!)
    }

    @Test fun `canary 180m flash has no czech`() {
        val s = sup("sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8")!!
        assertEquals(setOf("en", "es", "de", "fr"), s)
    }

    @Test fun `czech is selectable`() {
        assertEquals("Czech", Languages.name("cs"))
    }
}
