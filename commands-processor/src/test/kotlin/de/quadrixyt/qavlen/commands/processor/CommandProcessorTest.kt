@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package de.quadrixyt.qavlen.commands.processor

import com.google.testing.compile.Compilation
import com.google.testing.compile.Compiler.javac
import com.google.testing.compile.JavaFileObjects
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandResult
import de.quadrixyt.qavlen.commands.CommandSource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandProcessorTest {
    private class Source(private val permissions: Set<String>) : CommandSource {
        val messages = mutableListOf<String>()
        override val name = "tester"
        override val id = "tester"
        override val locale: Locale = Locale.ENGLISH
        override val handle: Any = this

        override fun hasPermission(permission: String) = permission in permissions

        override fun sendMessage(message: String) {
            messages += message
        }
    }

    private fun compileKotlin(vararg sources: SourceFile): JvmCompilationResult = KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        messageOutputStream = System.out
        configureKsp { symbolProcessorProviders += CommandSymbolProcessorProvider() }
    }.compile()

    private fun compileJava(source: String): Compilation =
        javac().withProcessors(CommandAnnotationProcessor()).compile(JavaFileObjects.forSourceString("test.CoinsCommand", source))

    @Test
    fun `kotlin commands are generated and executable`() {
        val result = compileKotlin(
            SourceFile.kotlin(
                "CoinsCommand.kt",
                """
                package test

                import de.quadrixyt.qavlen.commands.CommandContext
                import de.quadrixyt.qavlen.commands.annotations.*

                enum class Reason { GIFT, REFUND }

                @Command("coins", aliases = ["money"], description = "Manage coins")
                @Permission("coins.use")
                class CoinsCommand {
                    val log = mutableListOf<String>()

                    @Execute
                    fun balance(context: CommandContext) { context.reply("balance") }

                    @Subcommand("give", aliases = ["add"])
                    @Permission("coins.admin")
                    @Cooldown(5)
                    fun give(target: String, @Range(min = 1.0, max = 100.0) amount: Int, @Optional reason: Reason?) {
                        log += "${'$'}target:${'$'}amount:${'$'}reason"
                    }

                    @Subcommand("admin reset")
                    fun reset(@Arg("player-name") playerName: String, @Greedy @Optional note: String?) {
                        log += "reset:${'$'}playerName:${'$'}note"
                    }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val type = result.classLoader.loadClass("test.CoinsCommand")
        val instance = type.getDeclaredConstructor().newInstance()
        val dispatcher = CommandDispatcher()
        val definition = dispatcher.register(instance)
        val source = Source(setOf("coins.use", "coins.admin"))

        assertEquals(listOf("money"), definition.aliases)
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(source, "coins"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(source, "money add Alex 10 refund"))
        assertEquals(CommandResult.COOLDOWN, dispatcher.execute(source, "coins give Alex 10"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(source, "coins admin reset Sam was cheating"))
        assertEquals(CommandResult.NO_PERMISSION, dispatcher.execute(Source(setOf("coins.use")), "coins give Alex 1"))
        dispatcher.cooldowns.reset("tester:coins give")
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(source, "coins give Alex 101"))

        @Suppress("UNCHECKED_CAST")
        val log = type.getMethod("getLog").invoke(instance) as List<String>
        assertEquals(listOf("Alex:10:REFUND", "reset:Sam:was cheating"), log)
        assertEquals("balance", source.messages.first())
        assertEquals(
            "/coins admin reset <player-name> [note]",
            "/coins admin " + definition.subcommand("admin")!!.subcommand("reset")!!.usage(),
        )
    }

    @Test
    fun `invalid kotlin signatures produce readable errors`() {
        val result = compileKotlin(
            SourceFile.kotlin(
                "Broken.kt",
                """
                package test

                import de.quadrixyt.qavlen.commands.annotations.*

                @Command("Broken")
                class Broken {
                    @Subcommand("list")
                    fun list(page: Int?, names: List<String>): Int = 0

                    @Subcommand("list")
                    suspend fun again() {}

                    @Subcommand("say")
                    fun say(@Greedy text: String, @Range(min = 1.0) target: String) {}

                    @Subcommand("opt")
                    fun opt(@Optional a: String?, b: String) {}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        listOf(
            "Command name 'Broken' must be 1-32 lowercase letters",
            "Argument 'names' has an unsupported type: collections and arrays are not supported",
            "Argument 'page' is nullable; annotate it with @Optional",
            "Command method 'list' must not return a value",
            "Subcommand 'list' is declared more than once",
            "Command methods must not be suspend functions",
            "@Greedy is only allowed on the last argument",
            "@Range requires an Int, Long or Double argument",
            "Required argument 'b' must come before optional arguments",
        ).forEach { expected -> assertTrue(expected in result.messages, "Missing '$expected' in:\n${result.messages}") }
    }

    @Test
    fun `java commands are generated`() {
        val compilation = compileJava(
            """
            package test;

            import de.quadrixyt.qavlen.commands.CommandContext;
            import de.quadrixyt.qavlen.commands.annotations.*;

            @Command(name = "coins", aliases = {"money"})
            public class CoinsCommand {
                @Subcommand(path = "give")
                @Permission("coins.admin")
                public void give(CommandContext context, String target, @Range(min = 1, max = 100) int amount, @Optional Integer bonus) {
                }

                @Subcommand(path = "admin reset")
                public void reset(@Arg(name = "player") String player, @Greedy String note) {
                }
            }
            """.trimIndent(),
        )

        assertEquals(Compilation.Status.SUCCESS, compilation.status(), compilation.diagnostics().joinToString("\n"))
        val generated = compilation.generatedSourceFile("test.CoinsCommand_QavlenCommand").get().getCharContent(true).toString()
        assertTrue(".argument(\"amount\", de.quadrixyt.qavlen.commands.ArgumentTypes.integer(1, 100), \"\")" in generated, generated)
        assertTrue(".optionalArgument(\"bonus\", types.resolve(java.lang.Integer.class), \"\")" in generated, generated)
        assertTrue(".argument(\"note\", de.quadrixyt.qavlen.commands.ArgumentTypes.greedyString(), \"\")" in generated, generated)
        assertTrue("command.give(context, context.argument(\"target\", java.lang.String.class)" in generated, generated)
    }

    @Test
    fun `invalid java signatures produce readable errors`() {
        val compilation = compileJava(
            """
            package test;

            import de.quadrixyt.qavlen.commands.annotations.*;

            @Command(name = "coins")
            public class CoinsCommand {
                @Subcommand(path = "give")
                void give(@Optional int amount, String[] names) {
                }

                @Subcommand(path = "take")
                public static String take() { return ""; }
            }
            """.trimIndent(),
        )

        assertEquals(Compilation.Status.FAILURE, compilation.status())
        val messages = compilation.errors().joinToString("\n") { it.getMessage(Locale.ENGLISH) }
        listOf(
            "Command method 'give' must be public",
            "Optional argument 'amount' must use a nullable or boxed type",
            "Argument 'names' has an unsupported type: arrays are not supported",
            "Command methods must not be static",
            "Command method 'take' must not return a value",
        ).forEach { expected -> assertTrue(expected in messages, "Missing '$expected' in:\n$messages") }
    }
}
