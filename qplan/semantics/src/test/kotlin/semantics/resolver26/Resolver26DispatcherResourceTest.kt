package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.AfterAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class Resolver26DispatcherResourceTest : Resolver26DispatcherResource {
    @Test
    fun `first per-method instance sees the class dispatcher`() {
        assertSame(resolverDispatcher, resolverDispatcher)
        observedDispatchers += resolverDispatcher
    }

    @Test
    fun `second per-method instance sees the same class dispatcher`() {
        assertSame(resolverDispatcher, resolverDispatcher)
        observedDispatchers += resolverDispatcher
    }

    private companion object {
        val observedDispatchers = ConcurrentHashMap.newKeySet<Any>()

        @JvmStatic
        @AfterAll
        fun verifyOneDispatcherForTheClass() {
            assertEquals(1, observedDispatchers.size)
            observedDispatchers.clear()
        }
    }
}
