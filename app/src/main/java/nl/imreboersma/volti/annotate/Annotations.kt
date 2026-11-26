package nl.imreboersma.volti.annotate

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlin.math.hypot

/**
 * One pen stroke on one page of a score. [scoreKey] says which score: `drive:<file id>` for an ensemble's, so a new
 * version keeps it; `local:<md5>` for a local file, so a rename keeps it. [inputs] is the stroke's input points in
 * page units, in androidx.ink's own format; the stroke is drawn again from those, its [color] and [size].
 */
@Entity(tableName = "annotation_stroke", indices = [Index(value = ["scoreKey"])])
data class AnnotationStroke(
    @PrimaryKey val id: String,
    val scoreKey: String,
    val page: Int,
    val color: Int,
    val size: Float,
    val inputs: ByteArray,
) {
    // A ByteArray compares by identity; the id is what makes a stroke the same one.
    override fun equals(other: Any?) = other is AnnotationStroke && other.id == id
    override fun hashCode() = id.hashCode()
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotation_stroke WHERE scoreKey = :scoreKey")
    suspend fun forScore(scoreKey: String): List<AnnotationStroke>

    @Insert
    suspend fun add(strokes: List<AnnotationStroke>)

    @Query("DELETE FROM annotation_stroke WHERE id IN (:ids)")
    suspend fun remove(ids: List<String>)
}

/** Whether a stroke through [points] passes within [radius] of [at]: the eraser touches it. All in page units. */
fun touches(points: List<Point>, at: Point, radius: Float): Boolean {
    if (points.size == 1) return hypot(points[0].x - at.x, points[0].y - at.y) <= radius
    return points.zipWithNext().any { (a, b) -> distanceToSegment(at, a, b) <= radius }
}

private fun distanceToSegment(p: Point, a: Point, b: Point): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length2 = dx * dx + dy * dy
    val t = if (length2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
