import org.example.Entity
import org.example.EntitySystem
import org.example.PositionVelocity
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.time.measureTimedValue

class EntitySystemTest {

    @Test
    fun addAndRemoveComponent() {
        val arena = Arena.ofAuto()
        val system = EntitySystem(arena, PositionVelocity)

        val entities = listOf(
            Entity(),
            Entity(),
            Entity(),
        )
        system.add(entities[0])

        assertEquals(system.entities.size, 1)

        system.add(entities[1])
        system.add(entities[2])

        assertEquals(system.entities.size, 3)

        system.forEach<PositionVelocity> { entityId, component ->
            if(entityId == entities[0]) {
                component.position.x = 10f
                component.position.y = 10f
            }
            if(entityId == entities[1]) {
                component.position.x = 20f
                component.position.y = 20f
            }
            if(entityId == entities[2]) {
                component.position.x = 30f
                component.position.y = 30f
            }
        }

        system.forEach<PositionVelocity> { entityId, component ->
            if(entityId == entities[0]) {
                assertEquals(10f,component.position.x)
                assertEquals(10f,component.position.y)
            }
            if(entityId == entities[1]) {
                assertEquals(20f,component.position.x)
                assertEquals(20f,component.position.y)
            }
            if(entityId == entities[2]) {
                assertEquals(30f,component.position.x)
                assertEquals(30f,component.position.y)
            }
        }

        system.removeAll(listOf(entities[0]))
        assertEquals(2, system.entities.size)

        system.forEach<PositionVelocity> { entityId, component ->
            if(entityId == entities[1]) {
                assertEquals(20f,component.position.x)
                assertEquals(20f,component.position.y)
            }
            if(entityId == entities[2]) {
                assertEquals(30f,component.position.x)
                assertEquals(30f,component.position.y)
            }
        }
    }

    @Test
    fun addAndRemoveLotsOfComponents() {
        val arena = Arena.ofAuto()
        val system = EntitySystem(arena, PositionVelocity)

        val entities = (0 until 1000000).map { Entity() }
        val start = System.nanoTime()
        system.addAll(entities)
        val timeTakenAddNs = System.nanoTime() - start
        println("Adding ${entities.size} " + TimeUnit.NANOSECONDS.toMillis(timeTakenAddNs) + " ms")
        system.removeAll(entities.take(entities.size/2))
        val timeTakenAllNs = System.nanoTime() - start
        println("Adding ${entities.size}, removing half of it took " + TimeUnit.NANOSECONDS.toMillis(timeTakenAllNs) + " ms")
    }
}