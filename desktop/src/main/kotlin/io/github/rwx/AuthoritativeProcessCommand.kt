package io.github.rwx

import java.io.File

/** Reuses a full JDK in development, or the current native launcher in a trimmed app runtime. */
internal object AuthoritativeProcessCommand {
    fun current(arguments: List<String>, stateDir: File, jvmOptions: List<String> = emptyList()): List<String> = build(
        File(System.getProperty("java.home")), System.getProperty("java.class.path"),
        System.getProperty("jpackage.app-path") ?: ProcessHandle.current().info().command().orElse(null),
        arguments, stateDir, jvmOptions,
    )

    internal fun build(javaHome: File, classpath: String, nativeExecutable: String?, arguments: List<String>,
                       stateDir: File, jvmOptions: List<String> = emptyList()): List<String> {
        // Launch4j distributions intentionally carry javaw.exe without java.exe.
        val java = listOf("java", "java.exe", "javaw.exe").map { File(javaHome, "bin/$it") }
            .firstOrNull { it.isFile && it.canExecute() }
        val entry = if (java != null) {
            buildList {
                add(java.absolutePath)
                addAll(listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.im=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED"))
                if (System.getProperty("os.name").startsWith("Mac")) add("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED")
                System.getProperty("rwx.assetsDir")?.let { add("-Drwx.assetsDir=$it") }
                addAll(jvmOptions)
                addAll(listOf("-cp", classpath, AuthoritativeDesktopMain::class.java.name))
            }
        } else {
            require(jvmOptions.isEmpty()) { "Native launchers cannot accept JVM test instrumentation" }
            val launcher = nativeExecutable?.let(::File)
            check(launcher?.isFile == true && launcher.canExecute()) { "The application launcher is unavailable" }
            listOf(launcher.absolutePath)
        }
        return entry + arguments + "--state-dir=${stateDir.absolutePath}"
    }
}
