package semantics.resolver26

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestScopeOwnershipTest {
    @Test
    fun `only orchestration and field task roots can launch on the Resolver26 request scope`() {
        val sourceDirectory = Path.of("src/main/kotlin/semantics/resolver26")
        assertTrue(Files.isDirectory(sourceDirectory), "Resolver26 source directory is missing")
        val sources =
            Files.list(sourceDirectory).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".kt") }.toList()
            }

        val rawRequestScopeLaunch = Regex("""requestScope\s*\.\s*launch\s*\(""")
        assertEquals(
            listOf("Resolver26RootTaskLauncher.kt"),
            sources.filter { source -> rawRequestScopeLaunch.containsMatchIn(source.readText()) }
                .map(Path::name)
                .sorted(),
        )

        val orchestrationRootLaunch = Regex("""\.\s*launchObjectOrchestrationTask\s*\{""")
        assertEquals(
            listOf("ObjectOrchestrationTask.kt"),
            sources.filter { source ->
                orchestrationRootLaunch.containsMatchIn(source.readText())
            }.map(Path::name)
                .sorted(),
        )

        val fieldRootLaunch = Regex("""\.\s*launchFieldResolverTask\s*\{""")
        assertEquals(
            listOf("FieldResolverTask.kt"),
            sources.filter { source -> fieldRootLaunch.containsMatchIn(source.readText()) }
                .map(Path::name)
                .sorted(),
        )
    }
}
