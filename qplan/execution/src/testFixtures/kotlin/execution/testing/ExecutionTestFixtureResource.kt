package execution.testing

import model.testing.TestWorld
import semantics.resolver26.Resolver26DispatcherResource

/**
 * Creates execution fixtures that borrow the dispatcher owned by the concrete JUnit test class.
 *
 * The inherited [Resolver26DispatcherResource] closes that dispatcher after the class. Fixtures
 * created here retain no owned execution resource and therefore require no per-test cleanup list.
 */
interface ExecutionTestFixtureResource : Resolver26DispatcherResource {
    fun fixtureFromResolverDSL(
        schemaSDL: String,
        resolverSchemaSDL: String,
    ): ExecutionTestFixture =
        ExecutionTestFixture.fromResolverDSL(
            schemaSDL = schemaSDL,
            resolverSchemaSDL = resolverSchemaSDL,
            resolverCoroutineContext = resolverDispatcher,
        )

    fun fixtureFromResolverDSL(resolverSchemaSDL: String): ExecutionTestFixture =
        ExecutionTestFixture.fromResolverDSL(
            resolverSchemaSDL = resolverSchemaSDL,
            resolverCoroutineContext = resolverDispatcher,
        )

    fun fixtureFromWorld(
        schemaSDL: String,
        world: TestWorld,
    ): ExecutionTestFixture =
        ExecutionTestFixture.fromWorld(
            schemaSDL = schemaSDL,
            world = world,
            resolverCoroutineContext = resolverDispatcher,
        )
}
