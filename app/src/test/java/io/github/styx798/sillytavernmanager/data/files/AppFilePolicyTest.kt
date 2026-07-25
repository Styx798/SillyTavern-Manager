package io.github.styx798.sillytavernmanager.data.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilePolicyTest {
    @Test
    fun commonConfigurationFilesAreEditable() {
        assertTrue(isEditableTextFile("settings.json", 1_024L))
        assertTrue(isEditableTextFile("config.yaml", 1_024L))
        assertTrue(isEditableTextFile("stm_settings.xml", 1_024L))
    }

    @Test
    fun binaryAndOversizedFilesAreNotEditable() {
        assertFalse(isEditableTextFile("archive.zip", 1_024L))
        assertFalse(isEditableTextFile("notes.txt", MAX_EDITABLE_FILE_BYTES + 1L))
    }
}
