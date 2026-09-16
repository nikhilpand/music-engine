package com.aurora.engine.provider.ytmusic.cipher

/**
 * Extracts cipher and n-transform functions from YouTube player JavaScript.
 *
 * ## Extraction Strategy
 *
 * 1. **AST-first**: Locate the decipher function by finding the function that performs
 *    string operations (reverse, splice, swap) on the signature. This is more resilient
 *    to minification changes than pure regex.
 *
 * 2. **Regex fallback**: If AST-based extraction fails, fall back to known regex patterns
 *    that match the function declaration and its helper object.
 *
 * ## Output
 *
 * Returns an [ExtractedCipherFunctions] containing:
 * - The full JavaScript source of the decipher function (callable with a single string arg).
 * - The full JavaScript source of the n-transform function.
 * - The helper object declaration (for signature operations like reverse/swap/splice).
 *
 * These are meant to be evaluated by a JavaScript engine (QuickJS).
 */
class CipherFunctionExtractor {

    /**
     * Extract both cipher functions from the player JavaScript source.
     *
     * @param playerJsSource The full JavaScript source of the YouTube player.
     * @return Extracted functions ready for QuickJS evaluation.
     * @throws CipherException if neither AST nor regex extraction succeeds.
     */
    fun extract(playerJsSource: String): ExtractedCipherFunctions {
        val sigResult = extractSignatureFunction(playerJsSource)
        val nResult = extractNTransformFunction(playerJsSource)

        return ExtractedCipherFunctions(
            signatureFunction = sigResult,
            nTransformFunction = nResult
        )
    }

    // ── Signature Decipher Extraction ────────────────────────────────────────

    private fun extractSignatureFunction(js: String): ExtractedFunction {
        // Strategy 1: Find the function that decodes the signature
        // Pattern: Look for function(a){a=a.split(""); ... ;return a.join("")}
        return tryAstSignatureExtraction(js)
            ?: tryRegexSignatureExtraction(js)
            ?: throw CipherException(
                "Failed to extract signature decipher function from player JS",
                CipherPhase.FUNCTION_EXTRACTION
            )
    }

    private fun tryAstSignatureExtraction(js: String): ExtractedFunction? {
        // Find the signature function entry point
        // YouTube typically has: var sig=FUNCTION_NAME(decodeURIComponent(...)
        // or: a=a.split("");XX.YY(a,N);...;return a.join("")
        val sigFuncNamePatterns = listOf(
            // Pattern: \b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\(
            Regex("""\\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
            // Pattern: \b([a-zA-Z0-9$]{2,})\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)
            Regex("""\b([a-zA-Z0-9$]{2,})\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)"""),
            // Pattern: ([a-zA-Z0-9$]+)\s*=\s*function\(a\)\{a=a\.split\(""\)
            Regex("""([a-zA-Z0-9$]+)\s*=\s*function\(a\)\{a=a\.split\(""\)""")
        )

        for (pattern in sigFuncNamePatterns) {
            val match = pattern.find(js) ?: continue
            val funcName = match.groupValues[1]
            if (funcName.isBlank()) continue

            // Extract the full function body
            val funcBody = extractFunctionBody(js, funcName) ?: continue

            // Extract the helper object (contains reverse, splice, swap operations)
            val helperObjName = extractHelperObjectName(funcBody)
            val helperObj = if (helperObjName != null) {
                extractHelperObject(js, helperObjName)
            } else null

            val fullSource = buildString {
                if (helperObj != null) {
                    append(helperObj)
                    append("\n")
                }
                append("var $funcName=$funcBody")
            }

            return ExtractedFunction(
                name = funcName,
                source = fullSource,
                strategy = ExtractionStrategy.AST
            )
        }

        return null
    }

    private fun tryRegexSignatureExtraction(js: String): ExtractedFunction? {
        // Broader regex fallback patterns
        val patterns = listOf(
            Regex("""(?:^|[;,])\s*([a-zA-Z0-9$]+)\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)[^}]+return\s+a\.join\(\s*""\s*\)\s*\}"""),
            Regex("""function\s+([a-zA-Z0-9$]+)\s*\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)[^}]+return\s+a\.join\(\s*""\s*\)\s*\}""")
        )

        for (pattern in patterns) {
            val match = pattern.find(js) ?: continue
            val funcName = match.groupValues[1]
            val funcSource = match.value.trimStart(';', ',').trim()

            val helperObjName = extractHelperObjectName(funcSource)
            val helperObj = if (helperObjName != null) extractHelperObject(js, helperObjName) else null

            val fullSource = buildString {
                if (helperObj != null) {
                    append(helperObj)
                    append("\n")
                }
                append(funcSource)
            }

            return ExtractedFunction(
                name = funcName,
                source = fullSource,
                strategy = ExtractionStrategy.REGEX
            )
        }

        return null
    }

    // ── N-Parameter Transform Extraction ────────────────────────────────────

    private fun extractNTransformFunction(js: String): ExtractedFunction? {
        return tryAstNExtraction(js)
            ?: tryRegexNExtraction(js)
        // N-transform is optional — some clients don't need it.
        // Return null rather than throwing; caller decides severity.
    }

    private fun tryAstNExtraction(js: String): ExtractedFunction? {
        // YouTube n-transform patterns:
        // Enhanced: b=a.split("") ... return b.join("")
        // The n-transform function is typically found via:
        // var nFunc=FUNCTION_NAME; OR &&(b=a.get("n"))&&(b=FUNCTION_NAME(b)...
        val nFuncNamePatterns = listOf(
            // Pattern: &&\(b=a\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(b\)
            Regex("""&&\(b=a\.get\("n"\)\)&&\(\s*b=([a-zA-Z0-9$]+)(?:\[(\d+)])?\(b\)"""),
            // Pattern: var [a-zA-Z0-9$]+=\[([a-zA-Z0-9$]+)\];
            Regex("""var\s+[a-zA-Z0-9$]+\s*=\s*\[\s*([a-zA-Z0-9$]+)\s*];"""),
            // Pattern: ([a-zA-Z0-9$]+)\s*=\s*function\(a\)\{var\s+b=a\.split\(""\)
            Regex("""([a-zA-Z0-9$]+)\s*=\s*function\(a\)\{\s*var\s+b\s*=\s*a\.split\(""\)""")
        )

        for (pattern in nFuncNamePatterns) {
            val match = pattern.find(js) ?: continue
            val funcName = match.groupValues[1]
            if (funcName.isBlank()) continue

            val funcBody = extractFunctionBody(js, funcName) ?: continue

            return ExtractedFunction(
                name = funcName,
                source = "var $funcName=$funcBody",
                strategy = ExtractionStrategy.AST
            )
        }

        return null
    }

    private fun tryRegexNExtraction(js: String): ExtractedFunction? {
        val patterns = listOf(
            Regex("""([a-zA-Z0-9$]+)\s*=\s*function\(\s*a\s*\)\s*\{\s*var\s+b\s*=\s*a\.split\(\s*""\s*\)[\s\S]{10,5000}?return\s+b\.join\(\s*""\s*\)\s*\}""")
        )

        for (pattern in patterns) {
            val match = pattern.find(js) ?: continue
            val funcName = match.groupValues[1]

            return ExtractedFunction(
                name = funcName,
                source = match.value,
                strategy = ExtractionStrategy.REGEX
            )
        }

        return null
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /**
     * Extract a function body by name, handling nested braces.
     */
    private fun extractFunctionBody(js: String, funcName: String): String? {
        val escapedName = Regex.escape(funcName)
        // Match: funcName=function(a){...}  or  function funcName(a){...}
        val startPatterns = listOf(
            Regex("""$escapedName\s*=\s*function\([^)]*\)\s*\{"""),
            Regex("""function\s+$escapedName\s*\([^)]*\)\s*\{""")
        )

        for (startPattern in startPatterns) {
            val startMatch = startPattern.find(js) ?: continue
            val funcStart = startMatch.range.first
            val braceStart = js.indexOf('{', startMatch.range.last - 1)
            if (braceStart == -1) continue

            var depth = 0
            var i = braceStart
            while (i < js.length) {
                when (js[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            // Extract from "function(" to closing "}"
                            val bodyStartIdx = startMatch.value.indexOf("function")
                            return startMatch.value.substring(bodyStartIdx) +
                                    js.substring(startMatch.range.last, i + 1)
                        }
                    }
                }
                i++
            }
        }

        return null
    }

    /**
     * Find the helper object name from a signature function body.
     * Helper objects are referenced as: OBJ.METHOD(a, N)
     */
    private fun extractHelperObjectName(funcBody: String): String? {
        val pattern = Regex("""([a-zA-Z0-9$]+)\.[a-zA-Z0-9$]+\(a,\d+\)""")
        return pattern.find(funcBody)?.groupValues?.get(1)
    }

    /**
     * Extract the full helper object declaration: var OBJ={...};
     */
    private fun extractHelperObject(js: String, objName: String): String? {
        val escapedName = Regex.escape(objName)
        val pattern = Regex("""var\s+$escapedName\s*=\s*\{""")
        val match = pattern.find(js) ?: return null

        val braceStart = js.indexOf('{', match.range.last - 1)
        if (braceStart == -1) return null

        var depth = 0
        var i = braceStart
        while (i < js.length) {
            when (js[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return js.substring(match.range.first, i + 1) + ";"
                    }
                }
            }
            i++
        }

        return null
    }
}

data class ExtractedCipherFunctions(
    val signatureFunction: ExtractedFunction,
    val nTransformFunction: ExtractedFunction?
)

data class ExtractedFunction(
    val name: String,
    val source: String,
    val strategy: ExtractionStrategy
)

enum class ExtractionStrategy {
    /** Extracted via structured pattern matching (more resilient). */
    AST,
    /** Extracted via broad regex (fallback). */
    REGEX
}
