package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhotosTest {
    @Test fun namesAreCleanedAndNeverOverwrite() {
        assertEquals("a_b.jpg", Photos.safeName("../../a b.JPG") { false })
        assertEquals("pic-2.png", Photos.safeName("pic.png") { it == "pic.png" || it == "pic-1.png" })
    }

    @Test fun onlyPhotoNamesAndBytesPass() {
        try { Photos.safeName("run.sh") { false }; fail() } catch (e: IllegalArgumentException) { }
        val jpg = ByteArray(20).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        assertTrue(Photos.looksLikeImage(jpg))
        assertFalse(Photos.looksLikeImage("#!/bin/sh and more text".toByteArray()))
    }
}
