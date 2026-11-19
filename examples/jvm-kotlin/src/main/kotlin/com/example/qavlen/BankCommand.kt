package com.example.qavlen

import de.quadrixyt.qavlen.commands.CommandContext
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.annotations.Arg
import de.quadrixyt.qavlen.commands.annotations.Command
import de.quadrixyt.qavlen.commands.annotations.Cooldown
import de.quadrixyt.qavlen.commands.annotations.Execute
import de.quadrixyt.qavlen.commands.annotations.Optional
import de.quadrixyt.qavlen.commands.annotations.Permission
import de.quadrixyt.qavlen.commands.annotations.Range
import de.quadrixyt.qavlen.commands.annotations.Subcommand
import de.quadrixyt.qavlen.language.placeholder

@Command("bank", aliases = ["money"], description = "bank.description")
class BankCommand(private val accounts: Accounts) {
    @Execute
    fun balance(context: CommandContext) {
        context.replyTranslated("bank.balance", placeholder("amount", accounts.balance(context.source.id)))
    }

    @Subcommand("deposit")
    @Cooldown(2)
    fun deposit(context: CommandContext, @Range(min = 1.0, max = 10_000.0) amount: Int) {
        val balance = accounts.add(context.source.id, amount.toLong())
        context.replyTranslated("bank.deposited", placeholder("amount", amount), placeholder("balance", balance))
    }

    @Subcommand("transfer")
    fun transfer(context: CommandContext, target: String, @Range(min = 1.0) amount: Int, @Optional @Arg(description = "bank.note") note: String?) {
        if (!accounts.transfer(context.source.id, target, amount.toLong())) {
            throw CommandException("bank.insufficient", placeholder("amount", amount))
        }
        context.replyTranslated("bank.transferred", placeholder("amount", amount), placeholder("target", target), placeholder("note", note ?: "-"))
    }

    @Subcommand("admin reset")
    @Permission("bank.admin")
    fun reset(context: CommandContext, target: String) {
        accounts.reset(target)
        context.replyTranslated("bank.reset", placeholder("target", target))
    }
}
