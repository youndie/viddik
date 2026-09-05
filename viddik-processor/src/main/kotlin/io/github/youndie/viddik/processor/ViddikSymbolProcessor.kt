package io.github.youndie.viddik.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

private const val DESKTOP_SCREENSHOT_FQN = "io.github.youndie.viddik.annotations.ViddikScreenshot"
private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"
private const val PREVIEW_PARAMETER_FQN = "androidx.compose.ui.tooling.preview.PreviewParameter"

// Compose Multiplatform 1.12 ships this exact fully-qualified name in common, which is also the one
// Android uses — so a single @Preview is understood by the IDE preview pane, by Android's screenshot
// tooling and by viddik. The legacy desktop-only
// androidx.compose.desktop.ui.tooling.preview.Preview is deliberately not read: it cannot serve
// Android, which is the entire reason for reading @Preview in the first place.
private const val PREVIEW_FQN = "androidx.compose.ui.tooling.preview.Preview"

// @Preview is repeatable, and a repeatable annotation read off an already-compiled declaration — which
// is exactly what a multipreview like @PreviewLightDark is — arrives wrapped in its container rather
// than as several annotations. Both shapes have to be unwrapped.
private const val PREVIEW_CONTAINER_FQN = "androidx.compose.ui.tooling.preview.Preview.Container"
private const val PREVIEW_WRAPPER_FQN = "androidx.compose.ui.tooling.preview.PreviewWrapper"

// A multipreview may itself be built out of multipreviews, so collection recurses. The cap is a
// backstop against an annotation cycle, which the Kotlin compiler permits between annotation classes.
private const val MAX_MULTIPREVIEW_DEPTH = 8
private const val GENERATED_PACKAGE = "io.github.youndie.viddik.generated"

private sealed class ViddikEntry {
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

public class ViddikSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val generateTests: Boolean = true,
) : SymbolProcessor {
    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val annotated = resolver.getSymbolsWithAnnotation(DESKTOP_SCREENSHOT_FQN).toList()
        if (annotated.isEmpty()) return emptyList()

        val entries = mutableListOf<ViddikEntry>()
        val sourceFiles = mutableListOf<KSFile>()

        for (symbol in annotated) {
            if (symbol !is KSFunctionDeclaration) {
                logger.error("@ViddikScreenshot can only be applied to functions", symbol)
                continue
            }

            val isComposable =
                symbol.annotations.any {
                    it.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == COMPOSABLE_FQN
                }
            if (!isComposable) {
                logger.error(
                    "@ViddikScreenshot function must also be annotated @Composable: ${symbol.qualifiedName?.asString()}",
                    symbol,
                )
                continue
            }

            val singleParam = symbol.parameters.singleOrNull()
            val previewParameterAnnotation =
                singleParam?.annotations?.firstOrNull {
                    it.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PREVIEW_PARAMETER_FQN
                }

            val providerQualifiedName: String?
            if (previewParameterAnnotation == null) {
                val hasOnlyDefaultableParams = symbol.parameters.all { it.hasDefault }
                if (!hasOnlyDefaultableParams) {
                    logger.error(
                        "@ViddikScreenshot function must take no required arguments (all parameters need " +
                            "default values), or a single parameter annotated @PreviewParameter: " +
                            "${symbol.qualifiedName?.asString()}",
                        symbol,
                    )
                    continue
                }
                providerQualifiedName = null
            } else {
                val providerArg =
                    previewParameterAnnotation.arguments.firstOrNull { it.name?.asString() == "provider" }?.value
                providerQualifiedName = (providerArg as? KSType)?.declaration?.qualifiedName?.asString()
                if (providerQualifiedName == null) {
                    logger.error(
                        "Could not resolve @PreviewParameter provider class for ${symbol.qualifiedName?.asString()}",
                        symbol,
                    )
                    continue
                }
            }

            val qualifiedName = symbol.qualifiedName?.asString()
            if (qualifiedName == null) {
                logger.error("@ViddikScreenshot function must have a qualified name", symbol)
                continue
            }

            val annotation =
                symbol.annotations.first {
                    it.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == DESKTOP_SCREENSHOT_FQN
                }
            val screenshotArgs =
                ScreenshotArgs(
                    name = annotation.argument("name") as? String,
                    group = annotation.argument("group") as? String,
                    width = annotation.argument("width") as? Int,
                    height = annotation.argument("height") as? Int,
                    darkVariant = annotation.argument("darkVariant") as? Boolean == true,
                    tolerancePercent = annotation.argument("tolerancePercent") as? Double,
                )

            val previewArgs = collectPreviews(symbol.annotations.toList(), depth = 0)

            val wrappers = collectWrappers(symbol.annotations.toList(), depth = 0).distinct()
            if (wrappers.size > 1) {
                logger.error(
                    "${symbol.qualifiedName?.asString()} resolves to more than one @PreviewWrapper " +
                        "(${wrappers.joinToString()}). A fixture can only be wrapped once.",
                    symbol,
                )
                continue
            }

            val fixtures =
                resolveFixtures(
                    functionName = symbol.simpleName.asString(),
                    rawScreenshot = screenshotArgs,
                    previews = previewArgs,
                    onError = { message -> logger.error("${symbol.qualifiedName?.asString()}: $message", symbol) },
                    onWarn = { message -> logger.warn("${symbol.qualifiedName?.asString()}: $message", symbol) },
                )
            if (fixtures.isEmpty()) continue

            fixtures.forEach { fixture ->
                entries += fixture.toEntries(qualifiedName, providerQualifiedName, wrappers.firstOrNull())
            }
            symbol.containingFile?.let { sourceFiles += it }
        }

        if (entries.isNotEmpty()) {
            val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
            generateRegistry(entries, dependencies)
            if (generateTests) generateTests(dependencies)
        }

        return emptyList()
    }

    private fun generateRegistry(
        entries: List<ViddikEntry>,
        dependencies: Dependencies,
    ) {
        val componentClass = ClassName("io.github.youndie.viddik.annotations", "ViddikComponent")
        val compositionLocalProvider = ClassName("androidx.compose.runtime", "CompositionLocalProvider")
        val localScreenshotDarkTheme = ClassName("io.github.youndie.viddik", "LocalViddikDarkTheme")
        val listOfComponent = LIST.parameterizedBy(componentClass)

        val initializer = CodeBlock.builder().add("buildList·{\n").indent()
        entries.forEach { entry ->
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
                    initializer.add(
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
                    val previewLabelClass =
                        ClassName("io.github.youndie.viddik.annotations", "ViddikPreviewLabel")
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
                    initializer.add(
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
                        initializer.add(
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
                }
            }
        }
        initializer.unindent().add("}")

        FileSpec
            .builder(GENERATED_PACKAGE, "GeneratedViddikRegistry")
            // GENERATED CODE MUST NOT FAIL A CONSUMER'S -Werror BUILD.
            //
            // The label lookup is written defensively — `param as? ViddikPreviewLabel` and a
            // `toString()` behind it — because a parameter provider may yield anything. When it
            // yields a final type that does not implement the interface, and `String` is the common
            // case, the compiler proves both dead and says so: "this cast can never succeed",
            // "redundant call of conversion method". Correct warnings about code nobody wrote by
            // hand and nobody can edit, and a module compiling with `allWarningsAsErrors` — which is
            // what the shared conventions turn on — fails on them.
            .addAnnotation(
                AnnotationSpec
                    .builder(Suppress::class)
                    .addMember("%S", "CAST_NEVER_SUCCEEDS")
                    .addMember("%S", "USELESS_CAST")
                    .addMember("%S", "USELESS_ELVIS")
                    .addMember("%S", "USELESS_CALL_ON_NOT_NULL")
                    .addMember("%S", "REDUNDANT_CALL_OF_CONVERSION_METHOD")
                    .build(),
            ).addType(
                TypeSpec
                    .objectBuilder("GeneratedViddikRegistry")
                    .addProperty(
                        PropertySpec
                            .builder("components", listOfComponent)
                            .initializer(initializer.build())
                            .build(),
                    ).build(),
            ).build()
            .writeTo(codeGenerator, dependencies)
    }

    private fun generateTests(dependencies: Dependencies) {
        val engineClass = ClassName("io.github.youndie.viddik.core", "ViddikEngine")
        val registryClass = ClassName(GENERATED_PACKAGE, "GeneratedViddikRegistry")
        val dynamicTestClass = ClassName("org.junit.jupiter.api", "DynamicTest")
        val testFactoryClass = ClassName("org.junit.jupiter.api", "TestFactory")

        FileSpec
            .builder(GENERATED_PACKAGE, "GeneratedViddikTests")
            .addType(
                TypeSpec
                    .classBuilder("GeneratedViddikTests")
                    .addFunction(
                        FunSpec
                            .builder("runAllScreenshots")
                            .addAnnotation(testFactoryClass)
                            .returns(LIST.parameterizedBy(dynamicTestClass))
                            .addStatement("return %T.dynamicTests(%T.components)", engineClass, registryClass)
                            .build(),
                    ).build(),
            ).build()
            .writeTo(codeGenerator, dependencies)
    }
}

private fun KSAnnotation.hasQualifiedName(fqn: String): Boolean = qualifiedName() == fqn

private fun KSAnnotation.qualifiedName(): String? =
    annotationType
        .resolve()
        .declaration.qualifiedName
        ?.asString()

private fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value

/**
 * Every `@Preview` an annotated function resolves to, in declaration order.
 *
 * A multipreview is an ordinary annotation class that carries `@Preview`s of its own, so finding them
 * means walking into annotations' declarations. `@PreviewLightDark` and friends target
 * `ANNOTATION_CLASS` as well as `FUNCTION`, which is to say a multipreview can be built out of
 * multipreviews — hence the recursion, and hence [MAX_MULTIPREVIEW_DEPTH], since the compiler happily
 * accepts a cycle between two annotation classes.
 */
private fun collectPreviews(
    annotations: List<KSAnnotation>,
    depth: Int,
): List<PreviewArgs> {
    if (depth > MAX_MULTIPREVIEW_DEPTH) return emptyList()
    return annotations.flatMap { annotation ->
        when (annotation.qualifiedName()) {
            PREVIEW_FQN -> {
                listOf(annotation.toPreviewArgs())
            }

            PREVIEW_CONTAINER_FQN -> {
                annotation.containedPreviews().map { it.toPreviewArgs() }
            }

            // Not a preview itself; it may still be an annotation class that carries some.
            in SKIPPED_ANNOTATIONS -> {
                emptyList()
            }

            else -> {
                collectPreviews(
                    annotation.annotationType
                        .resolve()
                        .declaration.annotations
                        .toList(),
                    depth + 1,
                )
            }
        }
    }
}

/** The same walk, for the wrapper a fixture should be composed inside. */
private fun collectWrappers(
    annotations: List<KSAnnotation>,
    depth: Int,
): List<String> {
    if (depth > MAX_MULTIPREVIEW_DEPTH) return emptyList()
    return annotations.flatMap { annotation ->
        when (annotation.qualifiedName()) {
            PREVIEW_WRAPPER_FQN -> {
                listOfNotNull(
                    (annotation.argument("wrapper") as? KSType)?.declaration?.qualifiedName?.asString(),
                )
            }

            PREVIEW_FQN, PREVIEW_CONTAINER_FQN -> {
                emptyList()
            }

            in SKIPPED_ANNOTATIONS -> {
                emptyList()
            }

            else -> {
                collectWrappers(
                    annotation.annotationType
                        .resolve()
                        .declaration.annotations
                        .toList(),
                    depth + 1,
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun KSAnnotation.containedPreviews(): List<KSAnnotation> =
    (argument("value") as? List<*>).orEmpty().filterIsInstance<KSAnnotation>()

private fun KSAnnotation.toPreviewArgs() =
    PreviewArgs(
        name = argument("name") as? String,
        group = argument("group") as? String,
        widthDp = argument("widthDp") as? Int ?: PREVIEW_UNSET_DP,
        heightDp = argument("heightDp") as? Int ?: PREVIEW_UNSET_DP,
        uiMode = argument("uiMode") as? Int ?: 0,
        fontScale = argument("fontScale") as? Float ?: 1f,
        device = argument("device") as? String ?: "",
    )

// Walking into these would resolve half of kotlin-stdlib's annotation graph on every fixture, and
// none of them can carry a @Preview.
private val SKIPPED_ANNOTATIONS =
    setOf(
        COMPOSABLE_FQN,
        DESKTOP_SCREENSHOT_FQN,
        PREVIEW_PARAMETER_FQN,
        "kotlin.Deprecated",
        "kotlin.Suppress",
        "kotlin.OptIn",
        "kotlin.jvm.JvmName",
    )

private fun FixtureMetadata.toEntries(
    qualifiedFunctionName: String,
    providerQualifiedName: String?,
    wrapperQualifiedName: String?,
): List<ViddikEntry> {
    if (providerQualifiedName != null) {
        return listOf(
            ViddikEntry.Parameterized(
                name = name,
                group = group,
                width = width,
                height = height,
                qualifiedFunctionName = qualifiedFunctionName,
                providerQualifiedName = providerQualifiedName,
                darkVariant = darkVariant,
                forceDark = dark,
                fontScale = fontScale,
                tolerancePercent = tolerancePercent,
                wrapperQualifiedName = wrapperQualifiedName,
            ),
        )
    }

    val base =
        ViddikEntry.Static(
            name = name,
            group = group,
            width = width,
            height = height,
            qualifiedFunctionName = qualifiedFunctionName,
            forceDark = dark,
            fontScale = fontScale,
            tolerancePercent = tolerancePercent,
            wrapperQualifiedName = wrapperQualifiedName,
        )
    return if (darkVariant) listOf(base, base.copy(name = "$name Dark", forceDark = true)) else listOf(base)
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
