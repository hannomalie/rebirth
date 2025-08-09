## Rebirth engine

I wanted to start from scratch and implement a game engine that has native support
for multithreaded rendering by using the extractor pattern based on Java's
new (currently still in preview) foreign memory api.

It's meant as an experiment to find out how my [struct generation library](https://github.com/hannomalie/StruktGen)
approach would work with the new foreign memory api and get some performance numbers.

Additionally, I wanted to see how a minimalistic and performant entity component system
with first class native memory support could look like on the API - similar to
how artemis-odb implemented "packed components" a century ago. In there, component data
is persisted directly in native memory, that can either be directly shared with the GPU
or at least directly copied over with a simple memcopy. Using it looks like that:

```kotlin
systems.add(object: System {
    override fun update(deltaSeconds: Float, arena: Arena) {
        parallelForEach<PositionVelocity> { index, component ->
            val position = component.position
            val velocity = component.velocity

            var resultingX = position.x + velocity.x * deltaSeconds
            var resultingY = position.y + velocity.y * deltaSeconds

            if(resultingX > dimension.width.toFloat()) {
                resultingX = 0f
            } else if(resultingX < 0) {
                resultingX = dimension.width.toFloat()
            }
            if(resultingY > dimension.height.toFloat()) {
                resultingY = 0f
            } else if(resultingY < 0) {
                resultingY = dimension.height.toFloat()
            }

            position.x = resultingX
            position.y = resultingY
        }
    }
})
```

In this example we have an anonymous system that queries PositionVelocity components and iterates them. This
component is already what other systems call an _archetype_, so a bag of multiple components.

The code necessary to implement such stuff is not exactly nice, but the idea is that you only define an interface
with some properties and the code generation will do that for you and also hides the ugly code. I didn't implement
a generator, the hand written code for an archetype would look like this:

```kotlin
open class PositionVelocity: Archetype {
    override val layout = MemoryLayout.structLayout(PositionComponent.layout.withName("position"), VelocityComponent.layout.withName("velocity"))

    val archetypeLayout = layout
    val position = object: PositionComponent() {
        override val xHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("position"),
            MemoryLayout.PathElement.groupElement("x")
        )
        override val yHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("position"),
            MemoryLayout.PathElement.groupElement("y")
        )
    }
    val velocity = object: VelocityComponent() {
        override val xHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("velocity"),
            MemoryLayout.PathElement.groupElement("x")
        )
        override val yHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("velocity"),
            MemoryLayout.PathElement.groupElement("y")
        )
    }
    override val includedComponents = setOf(position, velocity)

    override val factory: () -> Component = { PositionVelocity() as Component }

    final override val identifier = 1
    companion object: PositionVelocity()
}
```
And quite similar for the PositionComponent and the VelocityComponent, you can find them in the sources.

It works, it performs, it's cool. No, you probably won't ever have a usecase for it.

