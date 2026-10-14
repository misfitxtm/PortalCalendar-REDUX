package com.portal.calendar

/**
 * Turns what the offline recognizer heard into a list command. Deliberately strict: a misheard
 * sentence should do nothing, not create junk, so anything that doesn't match returns null.
 * Recognition itself is restricted to a fixed vocabulary ([grammar]), which is what makes a small
 * on-device model usable. Pure Kotlin so JUnit can test it.
 */
object VoiceCommands {
    sealed class Command {
        data class Shopping(val item: String) : Command()
        data class ToList(val item: String, val list: String) : Command()
    }

    private const val LEAD = "(?:please )?"
    private const val THE = "(?:the |my |our )?"
    private val SHOPPING = Regex("^$LEAD(?:add|put) (.+?) (?:(?:to|on|onto) )?${THE}shopping(?: list)?$")
    private val TO_LIST = Regex("^$LEAD(?:add|put) (.+?) (?:to|on|onto) $THE(.+?)(?: list)?$")

    fun interpret(spoken: String): Command? {
        val t = spoken.lowercase().replace("[unk]", " ").replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        SHOPPING.find(t)?.let { return item(it.groupValues[1])?.let(Command::Shopping) }
        TO_LIST.find(t)?.let { m ->
            val item = item(m.groupValues[1]) ?: return null
            val list = m.groupValues[2].trim().ifEmpty { return null }
            return Command.ToList(item, list)
        }
        return null
    }

    /** Sentence-case the item, and refuse empty or absurdly long guesses. */
    private val FILLER = setOf("to", "on", "onto", "the", "my", "our", "and", "a", "an", "some", "shopping", "list", "please")

    private fun item(raw: String): String? {
        val s = raw.trim().removePrefix("some ").removePrefix("a ").removePrefix("an ").trim()
        // A lone filler word is a misheard sentence, not an item.
        return if (s.isEmpty() || s.length > 60 || s in FILLER) null else s.replaceFirstChar { it.uppercase() }
    }

    fun describe(c: Command): String = when (c) {
        is Command.Shopping -> "Add “${c.item}” to the shopping list?"
        is Command.ToList -> "Add “${c.item}” to your ${c.list} list?"
    }

    const val HELP = "Try: “add paper towels to the shopping list” or “add eggs to groceries”."

    private val COMMAND_WORDS = "add put to on onto the my our shopping list please and a an some"

    // Everyday items beyond the Shopping presets; the family's own list items are added at runtime.
    private val STAPLES = ("milk eggs bread butter cheese yogurt cereal rice pasta flour sugar salt pepper oil " +
        "chicken beef pork fish turkey bacon sausage ham tuna apples bananas oranges grapes strawberries " +
        "blueberries lemons limes potatoes onions tomatoes carrots lettuce spinach broccoli cucumber peppers " +
        "mushrooms garlic celery corn beans peas avocado juice water soda coffee tea cookies crackers chips " +
        "snacks pizza bagels tortillas peanut jelly honey syrup ketchup mustard mayo salsa soup sauce oats " +
        "oat almond soy cream ice vegetables fruit frozen groceries lunch dinner breakfast").split(" ")

    /** JSON array for the recognizer: every word it is allowed to hear, plus [unk] for anything else. */
    fun grammar(extraWords: Collection<String>): String {
        val words = LinkedHashSet<String>()
        fun addAll(text: String) = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }.forEach(words::add)
        addAll(COMMAND_WORDS)
        STAPLES.forEach(words::add)
        Shopping.PRESETS.values.flatten().forEach(::addAll)
        extraWords.forEach(::addAll)
        return words.joinToString(prefix = "[", postfix = ",\"[unk]\"]", separator = ",") { "\"${it.replace("\"", "")}\"" }
    }
}
