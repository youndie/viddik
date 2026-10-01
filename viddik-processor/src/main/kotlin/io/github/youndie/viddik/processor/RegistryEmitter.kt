package io.github.youndie.viddik.processor

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal const val GENERATED_PACKAGE = "io.github.youndie.viddik.generated"
internal const val REGISTRY_NAME = "GeneratedViddikRegistry"

internal sealed class ViddikEntry {
    abstract val group: String

    data class Static(
        val name: String,
        override val group: String,
        val width: Int,
        val height: Int,
        val qualifiedFunctionName: String,
        val forceDark: Boolean = false,
        val fontScale: Float = 1f,
        val tolerancePercent: Double? = null,
        val wrapperQualifiedName: String? = null,
    ) : ViddikEntry()

    data class Parameterized(
        val name: String,
        override val group: String,
        val width: Int,
        val height: Int,
        val qualifiedFunctionName: String,
        val providerQualifiedName: String,
        val darkVariant: Boolean,
        val forceDark: Boolean = false,
        val fontScale: Float = 1f,
        val tolerancePercent: Double? = null,
        val wrapperQualifiedName: String? = null,
    ) : ViddikEntry()
}

private val componentClass = ClassName("io.github.youndie.viddik.annotations", "ViddikComponent")
private val previewLabelClass = ClassName("io.github.youndie.viddik.annotations", "ViddikPreviewLabel")
private val compositionLocalProvider = ClassName("androidx.compose.runtime", "CompositionLocalProvider")
private val localScreenshotDarkTheme = ClassName("io.github.youndie.viddik", "LocalViddikDarkTheme")
private val listOfComponent = LIST.parameterizedBy(componentClass)

/**
 * How many entries one generated file holds.
 *
 * The JVM caps a method at 64 KB of bytecode, and a registry built in one initializer put every
 * entry into `GeneratedViddikRegistry.<clinit>`: 2000 plain fixtures overflowed it, 1000 did not.
 * Splitting that initializer into functions is not enough on its own. The Compose compiler hoists
 * every `content` lambda that captures nothing into a `ComposableSingletons` class — one per *file*
 * — and initialises them all in that class's `<clinit>`, about 18 bytes a lambda: 55 KB at 3000
 * fixtures with the entries already spread over functions. So each chunk is a file of its own, with
 * a `ComposableSingletons` of its own, and the registry object only concatenates them.
 *
 * Measured at 200: the chunk's function is ~7 KB, its singletons ~4 KB. `RegistryCompileTest`
 * holds every generated method under a quarter of the limit.
 */
internal const val REGISTRY_CHUNK_SIZE = 200

/**
 * `GeneratedViddikRegistry` and its chunks, as files — kept free of KSP, like `FixtureMetadata.kt`,
 * so that what the processor emits can be compiled in a test without standing up a KSP run.
 *
 * `components` keeps its shape: an eagerly built `List<ViddikComponent>` on the object, in the
 * order the entries were given. The order is load-bearing — `ViddikEngine.dynamicTests` shards by
 * index — and concatenating the chunks in sequence preserves it. The chunk functions are `internal`
 * rather than private because they live in other files; they are not meant to be called.
 */
internal fun registryFiles(
    entries: List<ViddikEntry>,
    chunkSize: Int = REGISTRY_CHUNK_SIZE,
): List<FileSpec> {
    val chunks =
        entries.chunked(chunkSize).mapIndexed { index, chunk ->
            val body = CodeBlock.builder().add("return·buildList·{\n").indent()
            chunk.forEach { body.add(addStatement(it)) }
            body.unindent().add("}\n")
            FunSpec
                .builder("viddikRegistryChunk$index")
                .addModifiers(KModifier.INTERNAL)
                .returns(listOfComponent)
                .addCode(body.build())
                .build()
        }
    val initializer = CodeBlock.builder().add("buildList·{\n").indent()
    chunks.forEach { initializer.add("addAll(%N())\n", it) }
    initializer.unindent().add("}")

    val registry =
        FileSpec
            .builder(GENERATED_PACKAGE, REGISTRY_NAME)
            .addType(
                TypeSpec
                    .objectBuilder(REGISTRY_NAME)
                    .addProperty(
                        PropertySpec
                            .builder("components", listOfComponent)
                            .initializer(initializer.build())
                            .build(),
                    ).build(),
            ).build()
    return listOf(registry) +
        chunks.mapIndexed { index, chunk ->
            FileSpec
                .builder(GENERATED_PACKAGE, "${REGISTRY_NAME}Chunk$index")
                // GENERATED CODE MUST NOT FAIL A CONSUMER'S -Werror BUILD.
                //
                // The label lookup is written defensively — `param as? ViddikPreviewLabel` and a
                // `toString()` behind it — because a parameter provider may yield anything. When it
                // yields a final type that does not implement the interface, and `String` is the
                // common case, the compiler proves both dead and says so: "this cast can never
                // succeed", "redundant call of conversion method". Correct warnings about code nobody
                // wrote by hand and nobody can edit, and a module compiling with
                // `allWarningsAsErrors` — which is what the shared conventions turn on — fails on them.
                .addAnnotation(
                    AnnotationSpec
                        .builder(Suppress::class)
                        .addMember("%S", "CAST_NEVER_SUCCEEDS")
                        .addMember("%S", "USELESS_CAST")
                        .addMember("%S", "USELESS_ELVIS")
                        .addMember("%S", "USELESS_CALL_ON_NOT_NULL")
                        .addMember("%S", "REDUNDANT_CALL_OF_CONVERSION_METHOD")
                        .build(),
                ).addFunction(chunk)
                .build()
        }
}

/** The `add(...)`/`addAll(...)` statements one entry contributes to a `buildList` block. */
private fun addStatement(entry: ViddikEntry): CodeBlock =
    when (entry) {
        is ViddikEntry.Static -> {
            val call = wrapped(CodeBlock.of("%L()", entry.qualifiedFunctionName), entry.wrapperQualifiedName)
            val contentLambda =
                if (entry.forceDark) {
                    CodeBlock.of(
                        "{ %T(%T provides true) { %L } }",
                        compositionLocalProvider,
                        localScreenshotDarkTheme,
                        call,
                    )
                } else {
                    CodeBlock.of("{ %L }", call)
                }
            CodeBlock.of(
                "add(%T(name = %S, group = %S, width = %L, height = %L, fontScale = %Lf, %Lcontent = %L))\n",
                componentClass,
                entry.name,
                entry.group,
                entry.width,
                entry.height,
                entry.fontScale,
                toleranceArgument(entry.tolerancePercent),
                contentLambda,
            )
        }

        is ViddikEntry.Parameterized -> {
            val providerClass = ClassName.bestGuess(entry.providerQualifiedName)
            // A night-mode @Preview makes the fixture itself dark, so the base entry — not just
            // the extra darkVariant one below — has to be wrapped.
            val paramCall =
                wrapped(CodeBlock.of("%L(param)", entry.qualifiedFunctionName), entry.wrapperQualifiedName)
            val baseContent =
                if (entry.forceDark) {
                    CodeBlock.of(
                        "{·%T(%T·provides·true)·{·%L·}·}",
                        compositionLocalProvider,
                        localScreenshotDarkTheme,
                        paramCall,
                    )
                } else {
                    CodeBlock.of("{·%L·}", paramCall)
                }
            val statements = CodeBlock.builder()
            statements.add(
                "addAll(%T().values.mapIndexed·{·index,·param·->·\n" +
                    "··val·label·=·((param·as?·%T)?.previewLabel·?:·param.toString()).take(60)\n" +
                    "··%T(name·=·%S·+·\"·-·\"·+·label·+·\"·#\"·+·index,·group·=·%S,·width·=·%L,·height·=·%L,·" +
                    "fontScale·=·%Lf,·%Lcontent·=·%L)\n" +
                    "}.toList())\n",
                providerClass,
                previewLabelClass,
                componentClass,
                entry.name,
                entry.group,
                entry.width,
                entry.height,
                entry.fontScale,
                toleranceArgument(entry.tolerancePercent),
                baseContent,
            )
            if (entry.darkVariant) {
                statements.add(
                    "addAll(%T().values.mapIndexed·{·index,·param·->·\n" +
                        "··val·label·=·((param·as?·%T)?.previewLabel·?:·param.toString()).take(60)\n" +
                        "··%T(name·=·%S·+·\"·-·\"·+·label·+·\"·#\"·+·index·+·\"·Dark\",·" +
                        "group·=·%S,·width·=·%L,·height·=·%L,·" +
                        "fontScale·=·%Lf,·%Lcontent·=·{·%T(%T·provides·true)·{·%L·} })\n" +
                        "}.toList())\n",
                    providerClass,
                    previewLabelClass,
                    componentClass,
                    entry.name,
                    entry.group,
                    entry.width,
                    entry.height,
                    entry.fontScale,
                    toleranceArgument(entry.tolerancePercent),
                    compositionLocalProvider,
                    localScreenshotDarkTheme,
                    paramCall,
                )
            }
            statements.build()
        }
    }

/**
 * A fixture's own `tolerancePercent`, as an argument to splice into the `ViddikComponent(...)` call —
 * or nothing at all when it didn't state one.
 *
 * Emitted only when it was asked for, rather than always as `tolerancePercent = null`: the argument
 * doesn't exist on `ViddikComponent` before 0.3.1, and a registry that names it unconditionally would
 * stop compiling against an older `viddik-annotations` for every fixture in the module rather than for
 * the fixtures actually using the feature.
 */
private fun toleranceArgument(tolerancePercent: Double?): CodeBlock =
    tolerancePercent?.let { CodeBlock.of("tolerancePercent·=·%L,·", it) } ?: CodeBlock.of("")

/**
 * Composes a fixture call inside its `@PreviewWrapper`, if it declared one.
 *
 * The provider is instantiated at the call site rather than resolved through anything of viddik's:
 * `PreviewWrapperProvider.Wrap` is a `@Composable` member, so the generated code is the same shape a
 * developer would write by hand, and a provider that needs constructor arguments simply doesn't
 * compile — which is the right moment to find out.
 */
private fun wrapped(
    call: CodeBlock,
    wrapperQualifiedName: String?,
): CodeBlock =
    if (wrapperQualifiedName == null) {
        call
    } else {
        CodeBlock.of("%T().Wrap·{·%L·}", ClassName.bestGuess(wrapperQualifiedName), call)
    }
