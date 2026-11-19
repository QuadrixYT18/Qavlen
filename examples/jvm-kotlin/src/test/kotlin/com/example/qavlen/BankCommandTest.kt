package com.example.qavlen

import de.quadrixyt.qavlen.commands.CommandResult
import de.quadrixyt.qavlen.commands.CommandSource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class BankCommandTest {
    private class Source(override val locale: Locale, private val admin: Boolean = false) : CommandSource {
        val messages = mutableListOf<String>()
        override val name = "alex"
        override val id = "alex"
        override val handle: Any = this

        override fun hasPermission(permission: String) = admin

        override fun sendMessage(message: String) {
            messages += message
        }
    }

    @Test
    fun `generated command runs with translations`() {
        val dispatcher = createDispatcher(BankConfig(startingBalance = 10))
        val english = Source(Locale.ENGLISH)
        val german = Source(Locale.GERMAN)

        assertEquals(CommandResult.SUCCESS, dispatcher.execute(english, "bank"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(english, "money deposit 1500"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(german, "bank transfer sam 1"))
        assertEquals(CommandResult.REJECTED, dispatcher.execute(german, "bank transfer sam 999999"))
        assertEquals(CommandResult.NO_PERMISSION, dispatcher.execute(english, "bank admin reset sam"))

        assertEquals(
            listOf("Your balance is 10 coins.", "Deposited 1,500 coins. New balance: 1,510.", "You do not have permission to use this command."),
            english.messages,
        )
        assertEquals(listOf("Eine Münze an sam gesendet (Notiz: -).", "Du kannst keine 999.999 Münzen senden."), german.messages)
    }
}
