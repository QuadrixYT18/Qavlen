# JVM example (Kotlin)

A console bank account built with annotated commands processed by KSP, YAML configuration bound to a data class and English/German translations.

```shell
./gradlew :examples:jvm-kotlin:run --console=plain
```

Then type `bank`, `bank deposit 50` or `bank transfer alex 10 thanks`. The test in `src/test` runs the same commands without a console.
