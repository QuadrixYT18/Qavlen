package com.example.qavlen;

import de.quadrixyt.qavlen.commands.CommandContext;
import de.quadrixyt.qavlen.commands.annotations.Command;
import de.quadrixyt.qavlen.commands.annotations.Execute;
import de.quadrixyt.qavlen.commands.annotations.Greedy;
import de.quadrixyt.qavlen.commands.annotations.Optional;
import de.quadrixyt.qavlen.commands.annotations.Permission;
import de.quadrixyt.qavlen.commands.annotations.Range;
import de.quadrixyt.qavlen.commands.annotations.Subcommand;
import de.quadrixyt.qavlen.language.Placeholder;

@Command(name = "greet", aliases = {"hello"}, description = "greet.description")
public final class GreetCommand {
    public enum Style { FRIENDLY, FORMAL }

    @Execute
    public void help(CommandContext context) {
        context.replyTranslated("greet.help");
    }

    @Subcommand(path = "user")
    public void user(CommandContext context, String name, @Optional Style style) {
        String key = style == Style.FORMAL ? "greet.formal" : "greet.friendly";
        context.replyTranslated(key, Placeholder.of("name", name));
    }

    @Subcommand(path = "repeat")
    @Permission("greet.repeat")
    public void repeat(CommandContext context, @Range(min = 1, max = 5) int times, @Greedy String message) {
        for (int i = 0; i < times; i++) {
            context.reply(message);
        }
    }
}
