# Model Domain Guidance

## Scope

This project defines the carrier algebra for field-resolution reasoning. Follow [`../AGENTS.md`](../AGENTS.md) and the concrete API rules in [`guidelines.md`](./guidelines.md). Correctness, demand derivation, execution attribution, and checker interpretation belong in semantic domains outside the carrier.

`EngineResult` values are finite and well-founded. `Value.Simple` results use structural equality, OERs use reference equality, and lists use structural equality over their type expression and positional element equality. Use `sameCompletedResultAs` for explicit extensional comparison of completed result trees. Mutable OERs may gain validated exact value, field-check, and type-check promises monotonically; a written parent may retain a mutable child OER while that child gains promises. Self-reference and cyclic result graphs are outside the result domain.

`Schema` and `ResolverRegistry` are externally supplied canonical worlds. Test-fixture composition may decode schemas, lower source node resolvers, canonicalize variables, validate provider paths, and assemble registries; semantic code receives only the resulting interfaces and model-owned `FieldResolver` values.

## Variables And Keys

`Value.Variable.Template` is identified by its local name and defining concrete resolver field. Stamping at an exact OER path creates an occurrence-specific `Stamped` variable. Resolver01-03 bind `FromArgument` variables in request-local monotonic `Assumptions`; runtime `FromObjectField` binding is deferred.

Registry assembly compiles `FromObjectField` declarations to contained canonical key paths and enforces an argument-insensitive branch order combining ordinary resolver dependencies with provider-production-before-use edges. This is pre-reasoning validation, not runtime provider evaluation.

`Value.Key` is an open selection key. `Value.ObjectKey` refines it to a concrete object field while retaining open arguments. `Value.GroundKey` further requires ground arguments and is the only key admitted to `Value.Object`, OER field promises, exact paths, materialization, dependency ordering, and resolver application.

Ground inputs implement the opaque `OpenValue` and `OpenArguments` interfaces. Grounding throws on an unbound stamped variable or unstamped template.

## Output Representations

`EngineResult.Object` represents field-resolution results with independent value, field-check, and type-check promises. For a completed check, `true` means access is accepted and `false` means access is rejected. Object occurrences carry their own type checks, so `EngineResult.List` needs only positional values. `Value.Output` represents resolver outputs without checks; do not collapse the two.

Object construction is immutable by default. Opt-in mutable objects atomically install each absent exact promise once and throw on unset reads or repeated writes. Lists are immutable positional values and retain their element type expression.

Raw node references exist only as fixture inputs. Composition lowers them through `foo$bridge` producers and argumentless `T$Bridge.$node` loaders before semantic reasoning.

## Local Rules

Use canonical schema relations rather than Kotlin inheritance for GraphQL semantics. `FieldValues` and `ObjectFieldValues` deliberately throw outside their lookup domain; test membership before optional lookup.

Every key present in an object value belongs to its concrete object type. `PathComponent` contains only exact `Value.GroundKey` object steps and `Value.ListIndex` list steps. Response aliases and ordering remain outside field-resolution identity.
