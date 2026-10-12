package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop's `modelName.test.ts`, case for case, so the two cannot name a model differently. */
class ModelNameTest {

    @Test
    fun `drops the quant tag and turns separators into spaces`() {
        assertEquals("Qwen3 4B Instruct 2507", readableModelName("Qwen3-4B-Instruct-2507-Q4_K_M"))
        assertEquals("Sharp MiniCPM5 2B", readableModelName("Sharp-MiniCPM5-2B-Q6_K_XL"))
        assertEquals("Llama 3.2 3B Instruct", readableModelName("Llama-3.2-3B-Instruct-IQ4_XS"))
        assertEquals("gemma 3 4b it", readableModelName("gemma-3-4b-it-BF16"))
    }

    @Test
    fun `drops GGUF packaging and a left-over extension in either order`() {
        assertEquals("Phi 4 mini", readableModelName("Phi-4-mini-GGUF-Q8_0.gguf"))
        assertEquals("nomic embed text v1.5", readableModelName("nomic-embed-text-v1.5.Q4_K_M"))
    }

    @Test
    fun `keeps a name that is nothing but a quant rather than returning nothing`() {
        assertEquals("Q4_K_M", readableModelName("Q4_K_M"))
        assertEquals("My Model", readableModelName("My Model"))
    }
}
