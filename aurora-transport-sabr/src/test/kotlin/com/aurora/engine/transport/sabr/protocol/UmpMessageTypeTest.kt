package com.aurora.engine.transport.sabr.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UmpMessageTypeTest {

    @Test
    fun `known type IDs have readable names`() {
        assertThat(UmpMessageType.nameOf(UmpMessageType.ONESIE_HEADER)).isEqualTo("ONESIE_HEADER")
        assertThat(UmpMessageType.nameOf(UmpMessageType.MEDIA_HEADER)).isEqualTo("MEDIA_HEADER")
        assertThat(UmpMessageType.nameOf(UmpMessageType.MEDIA_DATA)).isEqualTo("MEDIA_DATA")
        assertThat(UmpMessageType.nameOf(UmpMessageType.MEDIA_END)).isEqualTo("MEDIA_END")
        assertThat(UmpMessageType.nameOf(UmpMessageType.FORMAT_INIT)).isEqualTo("FORMAT_INIT")
        assertThat(UmpMessageType.nameOf(UmpMessageType.NEXT_REQUEST_POLICY)).isEqualTo("NEXT_REQUEST_POLICY")
        assertThat(UmpMessageType.nameOf(UmpMessageType.RELOAD)).isEqualTo("RELOAD")
        assertThat(UmpMessageType.nameOf(UmpMessageType.REDIRECT)).isEqualTo("REDIRECT")
        assertThat(UmpMessageType.nameOf(UmpMessageType.STREAM_ERROR)).isEqualTo("STREAM_ERROR")
    }

    @Test
    fun `unknown type ID returns UNKNOWN with ID`() {
        assertThat(UmpMessageType.nameOf(999)).isEqualTo("UNKNOWN(999)")
        assertThat(UmpMessageType.nameOf(0)).isEqualTo("UNKNOWN(0)")
    }

    @Test
    fun `type constants have expected values`() {
        assertThat(UmpMessageType.ONESIE_HEADER).isEqualTo(10)
        assertThat(UmpMessageType.MEDIA_HEADER).isEqualTo(20)
        assertThat(UmpMessageType.MEDIA_DATA).isEqualTo(21)
        assertThat(UmpMessageType.MEDIA_END).isEqualTo(22)
        assertThat(UmpMessageType.FORMAT_INIT).isEqualTo(42)
        assertThat(UmpMessageType.NEXT_REQUEST_POLICY).isEqualTo(43)
        assertThat(UmpMessageType.RELOAD).isEqualTo(44)
        assertThat(UmpMessageType.REDIRECT).isEqualTo(45)
        assertThat(UmpMessageType.STREAM_ERROR).isEqualTo(46)
    }
}
