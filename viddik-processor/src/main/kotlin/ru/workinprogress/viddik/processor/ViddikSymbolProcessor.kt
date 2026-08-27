package ru.workinprogress.viddik.processor

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
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

private const val DESKTOP_SCREENSHOT_FQN = "ru.workinprogress.viddik.annotations.ViddikScreenshot"
private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"
private const val PREVIEW_PARAMETER_FQN = "androidx.compose.ui.tooling.preview.PreviewParameter"

// Compose Multiplatform 1.12 ships this exact fully-qualified name in common, which is also the one
// Android uses — so a single @Preview is understood by the IDE preview pane, by Android's screenshot
// tooling and by viddik. The legacy desktop-only
// androidx.compose.desktop.ui.tooling.preview.Preview is deliberately not read: it cannot serve
// Android, which is the entire reason for reading @Preview in the first place.
private const val PREVIEW_FQN = "androidx.compose.ui.tooling.preview.Preview"
private const val GENERATED_PACKAGE = "ru.workinprogress.viddik.generated"

private sealed class ViddikEntry {
    abstract val group: String

    data class Static(
        val name: String,
        override val group: String,
        val width: Int,
        val height: Int,
        val qualifiedFunctionName: String,
        val forceDark: Boolean = false,
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
    ) : ViddikEntry()
}

class ViddikSymbolProcessor(
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
                )

            val previewAnnotations =
                symbol.annotations.filter { it.hasQualifiedName(PREVIEW_FQN) }.toList()
            if (previewAnnotations.size > 1) {
                // @Preview is repeatable and one entry per annotation is the right answer, but that is a
                // change to the registry shape rather than to naming, so it is not read yet. Failing here
                // beats silently capturing the first of several and calling the rest recorded.
                logger.error(
                    "Reading more than one @Preview off a single function is not supported yet; " +
                        "${symbol.qualifiedName?.asString()} carries ${previewAnnotations.size}. Split them " +
                        "across functions for now.",
                    symbol,
                )
                continue
            }
            val previewArgs =
                previewAnnotations.firstOrNull()?.let { preview ->
                    PreviewArgs(
                        name = preview.argument("name") as? String,
                        group = preview.argument("group") as? String,
                        widthDp = preview.argument("widthDp") as? Int ?: PREVIEW_UNSET_DP,
                        heightDp = preview.argument("heightDp") as? Int ?: PREVIEW_UNSET_DP,
                        uiMode = preview.argument("uiMode") as? Int ?: 0,
                    )
                }

            val fixture =
                resolveFixture(
                    functionName = symbol.simpleName.asString(),
                    screenshot = screenshotArgs,
                    preview = previewArgs,
                ) { message -> logger.error("${symbol.qualifiedName?.asString()}: $message", symbol) }
                    ?: continue

            val resolvedName = fixture.name
            val resolvedGroup = fixture.group
            val resolvedWidth = fixture.width
            val resolvedHeight = fixture.height
            val darkVariantArg = fixture.darkVariant

            if (providerQualifiedName != null) {
                entries +=
                    ViddikEntry.Parameterized(
                        name = resolvedName,
                        group = resolvedGroup,
                        width = resolvedWidth,
                        height = resolvedHeight,
                        qualifiedFunctionName = qualifiedName,
                        providerQualifiedName = providerQualifiedName,
                        darkVariant = darkVariantArg,
                        forceDark = fixture.dark,
                    )
            } else {
                entries +=
                    ViddikEntry.Static(
                        name = resolvedName,
                        group = resolvedGroup,
                        width = resolvedWidth,
                        height = resolvedHeight,
                        qualifiedFunctionName = qualifiedName,
                        forceDark = fixture.dark,
                    )
                if (darkVariantArg) {
                    entries +=
                        ViddikEntry.Static(
                            name = "$resolvedName Dark",
                            group = resolvedGroup,
                            width = resolvedWidth,
                            height = resolvedHeight,
                            qualifiedFunctionName = qualifiedName,
                            forceDark = true,
                        )
                }
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
        val componentClass = ClassName("ru.workinprogress.viddik.annotations", "ViddikComponent")
        val compositionLocalProvider = ClassName("androidx.compose.runtime", "CompositionLocalProvider")
        val localScreenshotDarkTheme = ClassName("ru.workinprogress.viddik", "LocalViddikDarkTheme")
        val listOfComponent = LIST.parameterizedBy(componentClass)

        val initializer = CodeBlock.builder().add("buildList·{\n").indent()
        entries.forEach { entry ->
            when (entry) {
                is ViddikEntry.Static -> {
                    val contentLambda =
                        if (entry.forceDark) {
                            CodeBlock.of(
                                "{ %T(%T provides true) { %L() } }",
                                compositionLocalProvider,
                                localScreenshotDarkTheme,
                                entry.qualifiedFunctionName,
                            )
                        } else {
                            CodeBlock.of("{ %L() }", entry.qualifiedFunctionName)
                        }
                    initializer.add(
                        "add(%T(name = %S, group = %S, width = %L, height = %L, content = %L))\n",
                        componentClass,
                        entry.name,
                        entry.group,
                        entry.width,
                        entry.height,
                        contentLambda,
                    )
                }

                is ViddikEntry.Parameterized -> {
                    val providerClass = ClassName.bestGuess(entry.providerQualifiedName)
                    val previewLabelClass =
                        ClassName("ru.workinprogress.viddik.annotations", "ViddikPreviewLabel")
                    // A night-mode @Preview makes the fixture itself dark, so the base entry — not just
                    // the extra darkVariant one below — has to be wrapped.
                    val baseContent =
                        if (entry.forceDark) {
                            CodeBlock.of(
                                "{·%T(%T·provides·true)·{·%L(param)·}·}",
                                compositionLocalProvider,
                                localScreenshotDarkTheme,
                                entry.qualifiedFunctionName,
                            )
                        } else {
                            CodeBlock.of("{·%L(param)·}", entry.qualifiedFunctionName)
                        }
                    initializer.add(
                        "addAll(%T().values.mapIndexed·{·index,·param·->·\n" +
                            "··val·label·=·((param·as?·%T)?.previewLabel·?:·param.toString()).take(60)\n" +
                            "··%T(name·=·%S·+·\"·-·\"·+·label·+·\"·#\"·+·index,·group·=·%S,·width·=·%L,·height·=·%L,·" +
                            "content·=·%L)\n" +
                            "}.toList())\n",
                        providerClass,
                        previewLabelClass,
                        componentClass,
                        entry.name,
                        entry.group,
                        entry.width,
                        entry.height,
                        baseContent,
                    )
                    if (entry.darkVariant) {
                        initializer.add(
                            "addAll(%T().values.mapIndexed·{·index,·param·->·\n" +
                                "··val·label·=·((param·as?·%T)?.previewLabel·?:·param.toString()).take(60)\n" +
                                "··%T(name·=·%S·+·\"·-·\"·+·label·+·\"·#\"·+·index·+·\"·Dark\",·group·=·%S,·width·=·%L,·" +
                                "height·=·%L,·content·=·{·%T(%T·provides·true)·{·%L(param)·} })\n" +
                                "}.toList())\n",
                            providerClass,
                            previewLabelClass,
                            componentClass,
                            entry.name,
                            entry.group,
                            entry.width,
                            entry.height,
                            compositionLocalProvider,
                            localScreenshotDarkTheme,
                            entry.qualifiedFunctionName,
                        )
                    }
                }
            }
        }
        initializer.unindent().add("}")

        FileSpec
            .builder(GENERATED_PACKAGE, "GeneratedViddikRegistry")
            .addType(
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
        val engineClass = ClassName("ru.workinprogress.viddik.core", "ViddikEngine")
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

private fun KSAnnotation.hasQualifiedName(fqn: String): Boolean =
    annotationType
        .resolve()
        .declaration.qualifiedName
        ?.asString() == fqn

private fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value
