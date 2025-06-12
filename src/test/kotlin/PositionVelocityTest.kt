import org.example.PositionVelocity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.nio.ByteOrder

class PositionVelocityTest {
    @Test
    fun testComplexComponentLayout() {
        val sequenceLayout = MemoryLayout.sequenceLayout(10, PositionVelocity.layout)
        val segment = Arena.ofConfined().allocate(sequenceLayout)

        assertEquals(160, sequenceLayout.byteSize())

        segment.elements(PositionVelocity.layout).toList().forEachIndexed { index, subsegment ->
            context(subsegment) {
                PositionVelocity.position.x = index.toFloat()
                PositionVelocity.position.y = 20f
                PositionVelocity.velocity.x = 100f + index.toFloat()
                PositionVelocity.velocity.y = 120f
            }
        }
        segment.elements(PositionVelocity.layout).toList().forEachIndexed { index, subsegment ->
            context(subsegment) {
                assertEquals(index.toFloat(), PositionVelocity.position.x)
                assertEquals(20f, PositionVelocity.position.y)
                assertEquals(100f + index, PositionVelocity.velocity.x)
                assertEquals(120f, PositionVelocity.velocity.y)
            }
        }
        val byteBuffer = segment.asByteBuffer()
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().apply {
            assertEquals(0f, get(0))
            assertEquals(20f, get(1))
            assertEquals(100f, get(2))
            assertEquals(120f, get(3))
            assertEquals(1f, get(4))
            assertEquals(20f, get(5))
            assertEquals(101f, get(6))
            assertEquals(120f, get(7))
        }
    }

}