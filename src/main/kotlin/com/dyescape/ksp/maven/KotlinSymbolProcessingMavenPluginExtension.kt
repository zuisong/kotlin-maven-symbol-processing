package com.dyescape.ksp.maven

import com.google.devtools.ksp.KspCliOption
import com.google.devtools.ksp.KspCliOption.*
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.plugin.MojoExecution
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.RepositorySystem
import org.codehaus.plexus.component.annotations.Component
import org.codehaus.plexus.component.annotations.Requirement
import org.jetbrains.kotlin.maven.KotlinMavenPluginExtension
import org.jetbrains.kotlin.maven.PluginOption
import java.io.File

const val KSP_PLUGIN_NAME = "ksp"
const val KSP_PLUGIN_ID = "com.google.devtools.ksp.symbol-processing"

/**
 * Extension that enables KSP for the kotlin maven plugin
 */
@Component(role = KotlinMavenPluginExtension::class, hint = KSP_PLUGIN_NAME)
class KotlinSymbolProcessingMavenPluginExtension : KotlinMavenPluginExtension {
    private val outputDirOptions = arrayOf(JAVA_OUTPUT_DIR_OPTION, KOTLIN_OUTPUT_DIR_OPTION)

    @Requirement
    lateinit var system: RepositorySystem

    override fun isApplicable(project: MavenProject, execution: MojoExecution) = true

    override fun getCompilerPluginId() = KSP_PLUGIN_ID

    override fun getPluginOptions(project: MavenProject, execution: MojoExecution): List<PluginOption> {
        val scopes = execution.findScopes()
        val test = Artifact.SCOPE_TEST in scopes

        val userOptions = execution.findKspOptions()
        val defaultOptions = buildDefaultOptions(project, execution, scopes, test)

        val options = mutableMapOf<KspCliOption, String>()
        for ((option, defaultValue) in defaultOptions) {
            if (option in userOptions) {
                // the option was already specified by the user
                continue
            }
            options.put(option, defaultValue)
        }

        for (outputDirOption in outputDirOptions) {
            val dir = options[outputDirOption] ?: userOptions[outputDirOption]?.singleOrNull()

            if (test) {
                project.addTestCompileSourceRoot(dir)
            } else {
                project.addCompileSourceRoot(dir)
            }
        }

        return options.map { (option, value) -> PluginOption(KSP_PLUGIN_NAME, KSP_PLUGIN_ID, option.optionName, value) }
    }

    private fun buildDefaultOptions(
        project: MavenProject,
        execution: MojoExecution,
        scopes: Set<String>,
        test: Boolean
    ): MutableMap<KspCliOption, String> {
        val defaultOptions = mutableMapOf<KspCliOption, String>()

        if (!test) {
            defaultOptions[CLASS_OUTPUT_DIR_OPTION] = project.build.outputDirectory
            defaultOptions[JAVA_OUTPUT_DIR_OPTION] = project.generatedSourcesDir("ksp-java").path
            defaultOptions[KOTLIN_OUTPUT_DIR_OPTION] = project.generatedSourcesDir("ksp").path
            defaultOptions[RESOURCE_OUTPUT_DIR_OPTION] = project.build.outputDirectory
            defaultOptions[KSP_OUTPUT_DIR_OPTION] = project.buildDir("ksp").path
        } else {
            defaultOptions[CLASS_OUTPUT_DIR_OPTION] = project.build.testOutputDirectory
            defaultOptions[JAVA_OUTPUT_DIR_OPTION] = project.generatedTestSourcesDir("ksp-java").path
            defaultOptions[KOTLIN_OUTPUT_DIR_OPTION] = project.generatedTestSourcesDir("ksp").path
            defaultOptions[RESOURCE_OUTPUT_DIR_OPTION] = project.build.testOutputDirectory
            defaultOptions[KSP_OUTPUT_DIR_OPTION] = project.buildDir("ksp-test").path
        }

        defaultOptions[PROJECT_BASE_DIR_OPTION] = project.basedir.path
        defaultOptions[PROCESSOR_CLASSPATH_OPTION] = execution.constructProcessorClassPath(scopes)
        defaultOptions[CACHES_DIR_OPTION] = system.cacheDir("ksp").path
        defaultOptions[WITH_COMPILATION_OPTION] = "true"

        return defaultOptions
    }

    private fun MojoExecution.constructProcessorClassPath(scopes: Set<String>): String {
        val classPath = plugin.dependencies.filter { it.scope in scopes }.flatMap {
            val artifact = system.createDependencyArtifact(it)
            val resolved = system.resolve(ArtifactResolutionRequest().setArtifact(artifact))

            resolved.artifacts.mapNotNull(Artifact::getFile).filter(File::exists)
        }

        return classPath.joinToString(separator = File.pathSeparator) { it.path }
    }

    private fun MavenProject.buildDir(name: String): File {
        return File(build.directory, name)
    }

    private fun MavenProject.generatedSourcesDir(name: String): File {
        return File(buildDir("generated-sources"), name)
    }

    private fun MavenProject.generatedTestSourcesDir(name: String): File {
        return File(buildDir("generated-test-sources"), name)
    }

    private fun RepositorySystem.cacheDir(name: String): File {
        return File(createDefaultLocalRepository().basedir, ".cache").resolve(name)
    }

    private fun MojoExecution.findScopes() = when (mojoDescriptor.dependencyResolutionRequired) {
        ResolutionScope.COMPILE.id() -> setOf(
            Artifact.SCOPE_COMPILE,
            Artifact.SCOPE_SYSTEM,
            Artifact.SCOPE_PROVIDED,
        )

        ResolutionScope.RUNTIME.id() -> setOf(
            Artifact.SCOPE_COMPILE,
            Artifact.SCOPE_RUNTIME,
        )

        ResolutionScope.COMPILE_PLUS_RUNTIME.id() -> setOf(
            Artifact.SCOPE_COMPILE,
            Artifact.SCOPE_SYSTEM,
            Artifact.SCOPE_PROVIDED,
            Artifact.SCOPE_RUNTIME,
        )

        ResolutionScope.RUNTIME_PLUS_SYSTEM.id() -> setOf(
            Artifact.SCOPE_COMPILE,
            Artifact.SCOPE_SYSTEM,
            Artifact.SCOPE_RUNTIME,
        )

        ResolutionScope.TEST.id() -> setOf(
            Artifact.SCOPE_COMPILE,
            Artifact.SCOPE_SYSTEM,
            Artifact.SCOPE_PROVIDED,
            Artifact.SCOPE_RUNTIME,
            Artifact.SCOPE_TEST,
        )

        else -> emptySet()
    }
}
