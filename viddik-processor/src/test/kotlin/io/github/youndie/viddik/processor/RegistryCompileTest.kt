package io.github.youndie.viddik.processor

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FIXTURES = 3000
private const val FIXTURES_PER_FILE = 100
private const val FIXTURE_PACKAGE = "fixtures"

/**
 * A quarter of the JVM's 64 KB per method. Compiling at all only proves the registry fits at
 * [FIXTURES]; staying this far under it is what says it would fit at four times that. It is also
 * what caught the second limit: with the entries already split into functions, the Compose
 * compiler's `ComposableSingletons` for the one registry file was a 55 KB `<clinit>` at 3000.
 */
private const val METHOD_BUDGET = 16 * 1024

/**
 * Compiles a registry of [FIXTURES] fixtures with the Compose compiler, the way a consumer's
 * `compileTestKotlin` does.
 *
 * The limit this guards is the JVM's 64 KB per method, and it is reached in bytecode, not in source:
 * 2000 fixtures in one `buildList` became a `<clinit>` the backend refused ("Method too large"), 1000
 * did not. Only a compilation can see it, and only one with the Compose plugin applied, which is what
 * turns each `content` lambda into the composable-lambda bytecode that fills the method.
 *
 * Loading the class afterwards checks the other half: that the registry still lists every entry, in
 * the order the processor saw them. `ViddikEngine.dynamicTests` shards by index, so the order is part
 * of the contract, not a detail of the output.
 */
class RegistryCompileTest {
    @Test
    fun aRegistryOfThreeThousandFixturesCompilesAndKeepsItsOrder() {
        val work = Files.createTempDirectory("viddik-registry").toFile()
        try {
            val sources = File(work, "src").apply { mkdirs() }
            val classes = File(work, "classes").apply { mkdirs() }

            val entries = fixtureEntries()
            writeFixtureSources(sources)
            registryFiles(entries).forEach { it.writeTo(sources) }

            compile(sources, classes)

            val sizes = generatedMethodSizes(classes)
            // Without the Compose plugin there are no singletons, and the check would pass on half the bytecode.
            assertTrue(sizes.keys.any { it.startsWith("ComposableSingletons") }, "No ComposableSingletons among $sizes")
            val oversized = sizes.filterValues { it > METHOD_BUDGET }
            assertTrue(oversized.isEmpty(), "Generated methods over $METHOD_BUDGET bytes: $oversized")
            assertEquals(expectedNames(entries), registryNames(classes))
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Mostly plain fixtures — the shape that overflowed — with every other shape the emitter knows
     * mixed in, so a chunk boundary is crossed by each of them somewhere.
     */
    private fun fixtureEntries(): List<ViddikEntry> =
        (0 until FIXTURES).map { i ->
            val function = "$FIXTURE_PACKAGE.${fixtureName(i)}"
            val group = "Group ${i / FIXTURES_PER_FILE}"
            when (i % 50) {
                7 -> {
                    ViddikEntry.Parameterized(
                        name = "Param $i",
                        group = group,
                        width = 400,
                        height = -1,
                        qualifiedFunctionName = "$FIXTURE_PACKAGE.Labelled",
                        providerQualifiedName = "$FIXTURE_PACKAGE.Labels",
                        darkVariant = i % 100 == 7,
                    )
                }

                11 -> {
                    ViddikEntry.Static(
                        i.toString(),
                        group,
                        320,
                        200,
                        function,
                        wrapperQualifiedName = "$FIXTURE_PACKAGE.Frame",
                    )
                }

                13 -> {
                    ViddikEntry.Static(i.toString(), group, 400, -1, function, forceDark = true, tolerancePercent = 0.2)
                }

                else -> {
                    ViddikEntry.Static(
                        i.toString(),
                        group,
                        400,
                        -1,
                        function,
                        fontScale =
                            if (i % 50 ==
                                17
                            ) {
                                1.5f
                            } else {
                                1f
                            },
                    )
                }
            }
        }

    private fun expectedNames(entries: List<ViddikEntry>): List<String> =
        entries.flatMap { entry ->
            when (entry) {
                is ViddikEntry.Static -> {
                    listOf(entry.name)
                }

                is ViddikEntry.Parameterized -> {
                    val light = LABELS.mapIndexed { index, label -> "${entry.name} - $label #$index" }
                    if (entry.darkVariant) light + light.map { "$it Dark" } else light
                }
            }
        }

    private fun writeFixtureSources(dir: File) {
        val pkg = File(dir, FIXTURE_PACKAGE).apply { mkdirs() }
        File(pkg, "Support.kt").writeText(
            """
            package $FIXTURE_PACKAGE

            import androidx.compose.runtime.Composable

            // What the generated code needs from a provider is `values`, nothing more.
            class Labels {
                val values: Sequence<String> = sequenceOf(${LABELS.joinToString { "\"$it\"" }})
            }

            class Frame {
                @Composable
                fun Wrap(content: @Composable () -> Unit) {
                    content()
                }
            }

            @Composable
            fun Labelled(label: String) {
                label.length
            }
            """.trimIndent(),
        )
        (0 until FIXTURES).chunked(FIXTURES_PER_FILE).forEachIndexed { file, indices ->
            File(pkg, "Fixtures$file.kt").writeText(
                buildString {
                    appendLine("package $FIXTURE_PACKAGE")
                    appendLine()
                    appendLine("import androidx.compose.runtime.Composable")
                    indices.forEach { i ->
                        appendLine()
                        appendLine("@Composable")
                        appendLine("fun ${fixtureName(i)}() {}")
                    }
                },
            )
        }
    }

    private fun compile(
        sources: File,
        classes: File,
    ) {
        val composePlugin =
            File(
                Class
                    .forName("androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar")
                    .protectionDomain.codeSource.location
                    .toURI(),
            )
        val log = ByteArrayOutputStream()
        val exit =
            K2JVMCompiler().exec(
                PrintStream(log),
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target",
                "21",
                "-classpath",
                System.getProperty("java.class.path"),
                "-Xplugin=${composePlugin.absolutePath}",
                "-d",
                classes.absolutePath,
                sources.absolutePath,
            )
        assertEquals(ExitCode.OK, exit, log.toString())
    }

    /**
     * Bytecode length of every method in the generated package, keyed `Class.method` — the
     * registry, its chunks, and the `ComposableSingletons` classes the Compose compiler adds for
     * each of them.
     */
    private fun generatedMethodSizes(classes: File): Map<String, Int> =
        File(classes, GENERATED_PACKAGE.replace('.', '/'))
            .listFiles { file -> file.extension == "class" }
            .orEmpty()
            .flatMap { file ->
                codeLengths(file).map { (method, length) ->
                    "${file.nameWithoutExtension}.$method" to
                        length
                }
            }.toMap()

    private fun registryNames(classes: File): List<String> =
        URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader).use { loader ->
            val registry = loader.loadClass("$GENERATED_PACKAGE.$REGISTRY_NAME")
            val instance = registry.getField("INSTANCE").get(null)
            val components = registry.getMethod("getComponents").invoke(instance) as List<*>
            components.map { component -> component!!.javaClass.getMethod("getName").invoke(component) as String }
        }

    private fun fixtureName(i: Int) = "Fixture%04d".format(i)

    private companion object {
        val LABELS = listOf("short", "long")
    }
}

/**
 * `name -> code_length` for each method with a body, read straight off the class file (JVMS §4):
 * the constant pool for the names, then fields skipped, then each method's `Code` attribute. A
 * dozen lines of format here beats a bytecode library on the test classpath for one number.
 */
private fun codeLengths(classFile: File): List<Pair<String, Int>> =
    DataInputStream(ByteArrayInputStream(classFile.readBytes())).use { input ->
        input.skipBytes(8) // magic, minor, major
        val utf8 = arrayOfNulls<String>(input.readUnsignedShort())
        var index = 1
        while (index < utf8.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()

                3, 4 -> input.skipBytes(4)

                5, 6 -> input.skipBytes(8).also { index++ }

                // long and double take two slots
                7, 8, 16, 19, 20 -> input.skipBytes(2)

                9, 10, 11, 12, 17, 18 -> input.skipBytes(4)

                15 -> input.skipBytes(3)

                else -> error("Unknown constant pool tag $tag in $classFile")
            }
            index++
        }
        input.skipBytes(6) // access flags, this, super
        input.skipBytes(2 * input.readUnsignedShort()) // interfaces

        fun members(): List<Pair<String, Int>> =
            List(input.readUnsignedShort()) {
                input.skipBytes(2)
                val name = utf8[input.readUnsignedShort()]!!
                input.skipBytes(2)
                var code: Int? = null
                repeat(input.readUnsignedShort()) {
                    val attribute = utf8[input.readUnsignedShort()]
                    val length = input.readInt()
                    if (attribute == "Code") {
                        input.skipBytes(4) // max_stack, max_locals
                        code = input.readInt()
                        input.skipBytes(length - 8)
                    } else {
                        input.skipBytes(length)
                    }
                }
                name to (code ?: -1)
            }

        members() // fields
        members().filter { it.second >= 0 }
    }
