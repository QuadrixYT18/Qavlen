package com.example.qavlen.jda;

import de.quadrixyt.qavlen.commands.CommandContext;
import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.commands.annotations.Command;
import de.quadrixyt.qavlen.commands.annotations.Execute;
import de.quadrixyt.qavlen.commands.annotations.Optional;
import de.quadrixyt.qavlen.commands.annotations.Range;
import de.quadrixyt.qavlen.jda.JdaCommands;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;

import java.util.concurrent.ThreadLocalRandom;

public final class Bot {
    private Bot() {
    }

    @Command(name = "roll", description = "Roll a die")
    public static final class RollCommand {
        @Execute
        public void roll(CommandContext context, @Range(min = 2, max = 100) @Optional Integer sides, @Optional User forUser) {
            int max = sides != null ? sides : 6;
            String who = forUser != null ? forUser.getAsMention() : context.getSource().getName();
            context.reply(who + " rolled " + ThreadLocalRandom.current().nextInt(1, max + 1));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null) {
            throw new IllegalStateException("Set DISCORD_TOKEN to run the example bot");
        }
        JdaCommands commands = new JdaCommands(new CommandDispatcher());
        commands.register(new RollCommand());
        JDA jda = JDABuilder.createLight(token).addEventListeners(commands).build().awaitReady();
        boolean updated = commands.syncGlobal(jda).join();
        System.out.println(updated ? "Slash commands updated" : "Slash commands unchanged");
    }
}
