package com.aurora.engine.provider.ytmusic.cipher

import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.assertThrows

class CipherFunctionExtractorTest {

    private val extractor = CipherFunctionExtractor()

    /**
     * Synthetic player JS that mimics YouTube's signature decipher pattern.
     * This is a minimal valid structure, NOT real YouTube code.
     */
    private val syntheticPlayerJs = """
        // Minified YouTube player JS (synthetic)
        var aB={
            rR:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},
            wQ:function(a){a.reverse()},
            Hy:function(a,b){a.splice(0,b)}
        };
        var Xk=function(a){a=a.split("");aB.wQ(a,44);aB.rR(a,6);aB.Hy(a,2);return a.join("")};
        
        var nTransform=function(a){var b=a.split("");b.reverse();return b.join("")};
        
        &&(b=a.get("n"))&&(b=nTransform(b),a.set("n",b));
    """.trimIndent()

    @Test
    fun `extracts signature function from synthetic JS`() {
        val result = extractor.extract(syntheticPlayerJs)

        assertThat(result.signatureFunction).isNotNull()
        assertThat(result.signatureFunction.name).isEqualTo("Xk")
        assertThat(result.signatureFunction.source).contains("split")
        assertThat(result.signatureFunction.source).contains("join")
    }

    @Test
    fun `signature function includes helper object`() {
        val result = extractor.extract(syntheticPlayerJs)
        val source = result.signatureFunction.source

        // Helper object should be included for the sig function to work
        assertThat(source).contains("aB")
    }

    @Test
    fun `extracts n-transform function from synthetic JS`() {
        val result = extractor.extract(syntheticPlayerJs)

        assertThat(result.nTransformFunction).isNotNull()
        assertThat(result.nTransformFunction!!.source).contains("split")
    }

    @Test
    fun `reports extraction strategy`() {
        val result = extractor.extract(syntheticPlayerJs)

        // Both should use AST or REGEX — either is valid for synthetic data
        assertThat(result.signatureFunction.strategy).isAnyOf(
            ExtractionStrategy.AST, ExtractionStrategy.REGEX
        )
    }

    @Test
    fun `throws on empty JavaScript`() {
        assertThrows<CipherException> {
            extractor.extract("")
        }
    }

    @Test
    fun `throws on JavaScript without cipher function`() {
        assertThrows<CipherException> {
            extractor.extract("var x = 42; console.log(x);")
        }
    }

    @Test
    fun `n-transform is optional`() {
        // JS with only sig function, no n-transform
        val sigOnly = """
            var hObj={
                ab:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},
                cd:function(a){a.reverse()}
            };
            var sigFunc=function(a){a=a.split("");hObj.cd(a,1);hObj.ab(a,3);return a.join("")};
        """.trimIndent()

        val result = extractor.extract(sigOnly)
        assertThat(result.signatureFunction).isNotNull()
        assertThat(result.nTransformFunction).isNull()
    }
}
