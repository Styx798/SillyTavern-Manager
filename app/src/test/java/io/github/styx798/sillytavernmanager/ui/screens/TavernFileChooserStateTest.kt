package io.github.styx798.sillytavernmanager.ui.screens

import android.net.Uri
import android.webkit.ValueCallback
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TavernFileChooserStateTest {
    @Test
    fun `completes the active file chooser once`() {
        val state = TavernFileChooserState()
        var observed: Array<Uri>? = null
        val selected = emptyArray<Uri>()

        state.begin(ValueCallback { observed = it })
        state.complete(selected)
        state.cancel()

        assertSame(selected, observed)
    }

    @Test
    fun `replacing a chooser cancels the old callback`() {
        val state = TavernFileChooserState()
        var oldResult: Array<Uri>? = emptyArray()
        var newResult: Array<Uri>? = emptyArray()

        state.begin(ValueCallback { oldResult = it })
        state.begin(ValueCallback { newResult = it })

        assertNull(oldResult)
        state.cancel()
        assertNull(newResult)
    }
}
