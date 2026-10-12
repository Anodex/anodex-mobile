package dev.anodex.mobile.chat

/**
 * A local model's file name as a person would say it: "Qwen3-4B-Instruct-2507-Q4_K_M"
 * becomes "Qwen3 4B Instruct 2507".
 *
 * The desktop's `readableModelName` (`src/renderer/lib/modelName.ts`), rule for rule,
 * so a model is called the same thing on both screens. The quant tag and the "GGUF"
 * packaging say how the file was made, not which model it is. Display only: a model is
 * still identified, and loaded, by its path.
 */
fun readableModelName(name: String): String {
    var readable = name.replace(GGUF_EXTENSION, "")
    if (QUANT_ONLY.matches(readable)) return readable
    repeat(2) {
        readable = readable.replace(QUANT_SUFFIX, "").replace(PACKAGING_SUFFIX, "")
    }
    readable = readable
        .replace(SEPARATORS, " ")
        .replace(SPACES, " ")
        .trim()
    return readable.ifEmpty { name }
}

private val GGUF_EXTENSION = Regex("\\.gguf$", RegexOption.IGNORE_CASE)
private val QUANT_SUFFIX =
    Regex("[-_. ]+(Q\\d(?:_[A-Z0-9]+)*|F16|F32|BF16|IQ\\d[A-Z0-9_]*)$", RegexOption.IGNORE_CASE)
private val QUANT_ONLY =
    Regex("^(Q\\d(?:_[A-Z0-9]+)*|F16|F32|BF16|IQ\\d[A-Z0-9_]*)$", RegexOption.IGNORE_CASE)
private val PACKAGING_SUFFIX = Regex("[-_. ]+(GGUF|GGML)$", RegexOption.IGNORE_CASE)
private val SEPARATORS = Regex("[-_]+")
private val SPACES = Regex("\\s{2,}")
