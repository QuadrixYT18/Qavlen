# Getting Started

Qavlen requires **Java 25**. Kotlin projects need Kotlin 2.4 or newer.

1. Add the JitPack repository and the modules you need, see [Installation](Installation).
2. If you declare commands with annotations, add `qavlen-commands-processor` to `ksp` (Kotlin) or `annotationProcessor` (Java).
3. Create a `CommandDispatcher`, register your commands and connect it to your platform.

A complete console application:

```kotlin
@Command("hello")
class HelloCommand {
    @Execute
    fun greet(context: CommandContext, @Optional name: String?) {
        context.reply("Hello ${name ?: context.source.name}!")
    }
}

fun main() {
    val dispatcher = CommandDispatcher()
    dispatcher.register(HelloCommand())
    val console = object : CommandSource {
        override val name = "console"
        override val id = "console"
        override val locale: Locale = Locale.ENGLISH
        override val handle: Any = this
        override fun hasPermission(permission: String) = true
        override fun sendMessage(message: String) = println(message)
    }
    generateSequence(::readlnOrNull).forEach { dispatcher.execute(console, it) }
}
```

On Paper, Velocity and Discord the platform module provides the `CommandSource`; see [Paper](Paper), [Velocity](Velocity) and [JDA](JDA).
