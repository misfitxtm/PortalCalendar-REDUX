package com.portal.calendar

import com.portal.calendar.VoiceCommands.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    private fun say(s: String) = VoiceCommands.interpret(s)

    @Test fun shoppingPhrases() {
        assertEquals(Command.Shopping("Oat milk"), say("add oat milk to the shopping list"))
        assertEquals(Command.Shopping("Paper towels"), say("Please put some paper towels on my shopping"))
        assertEquals(Command.Shopping("Batteries"), say("add batteries to shopping list."))
        assertEquals(Command.Shopping("Bread and water"), say("add bread and water the shopping list")) // "to" dropped by the recognizer
    }

    @Test fun otherListsMustNotSwallowShopping() {
        assertEquals(Command.ToList("Eggs", "groceries"), say("add eggs to groceries"))
        assertEquals(Command.ToList("Socks", "packing"), say("put socks on the packing list"))
    }

    @Test fun misheardOrUnknownDoesNothing() {
        assertNull(say(""))
        assertNull(say("what is the weather"))
        assertNull(say("turn off the lights"))
        assertNull(say("remind aiden to feed the dog")) // chores are not voice commands
        assertNull(say("add to shopping"))
        assertNull(say("[unk] [unk]"))
    }

    @Test fun overlongItemIsRefused() {
        assertNull(say("add " + "word ".repeat(30) + "to shopping"))
    }

    @Test fun grammarCoversCommandsPresetsAndFamilyWords() {
        val g = VoiceCommands.grammar(listOf("Oat Milk", "Gluten-free bread", "Groceries"))
        for (w in listOf("add", "shopping", "list", "paper", "towels", "milk", "oat", "gluten", "groceries", "[unk]"))
            assertTrue("missing $w in $g", g.contains("\"$w\""))
        assertEquals(1, Regex("\"milk\"").findAll(g).count()) // de-duplicated
    }
}
