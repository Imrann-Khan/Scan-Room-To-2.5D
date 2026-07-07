package com.example.camerawitharcore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** A point in the room's own local space: x/z = floor plan position, y = height off the floor. */
data class Point3D(val x: Float, val y: Float, val z: Float)

/** Four corners of one extruded wall rectangle, floor-to-ceiling. */
data class WallFace(val floorA: Point3D, val floorB: Point3D, val ceilB: Point3D, val ceilA: Point3D)

/**
 * Step 5: wall extrusion + 2.5D isometric render.
 *
 * Reads the room.json saved by the capture screen, turns the floor polygon
 * into wall rectangles (the "edge -> face" step from earlier), and draws
 * the whole thing as a 2.5D isometric illustration — floor + walls, no
 * roof, viewed from a fixed angle. This is pure 2D drawing math (Compose
 * Canvas), not a 3D engine: isometric projection just means we compute
 * where each 3D point would land on screen from a fixed camera angle.
 */
class RoomViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val file = File(filesDir, "room.json")
        val room = if (file.exists()) loadRoom(file) else null

        setContent {
            if (room == null) {
                Text("No room.json found yet — capture and finish a room first.")
            } else {
                RoomViewer2_5D(room.first, room.second)
            }
        }
    }
}

/** Loads floor corners (local coordinates, first corner at origin) + wall height from room.json. */
private fun loadRoom(file: File): Pair<List<Point3D>, Float> {
    val root = JSONObject(file.readText())
    val pointsArray = root.getJSONArray("points")
    val height = root.getDouble("wallHeight").toFloat()

    val rawPoints = (0 until pointsArray.length()).map { i ->
        val p = pointsArray.getJSONObject(i)
        Point3D(p.getDouble("x").toFloat(), 0f, p.getDouble("z").toFloat())
    }

    // Re-center so the room sits near the origin regardless of where in the
    // real world it was scanned — ARCore's world coordinates are arbitrary
    // and can be far from (0,0,0) depending on where the session started.
    val originX = rawPoints[0].x
    val originZ = rawPoints[0].z
    val localPoints = rawPoints.map { Point3D(it.x - originX, 0f, it.z - originZ) }

    return localPoints to height
}

/** Builds one WallFace per polygon edge, extruding straight up by wallHeight. */
private fun extrudeWalls(floorCorners: List<Point3D>, wallHeight: Float): List<WallFace> {
    return floorCorners.indices.map { i ->
        val a = floorCorners[i]
        val b = floorCorners[(i + 1) % floorCorners.size] // wrap to close the loop
        WallFace(
            floorA = a,
            floorB = b,
            ceilB = Point3D(b.x, wallHeight, b.z),
            ceilA = Point3D(a.x, wallHeight, a.z)
        )
    }
}

/** Standard 2:1 isometric projection: (x, y, z) in meters -> 2D screen offset. */
private fun isoProject(p: Point3D, scale: Float): Offset {
    val isoX = (p.x - p.z) * scale * cos(Math.toRadians(30.0)).toFloat()
    val isoY = (p.x + p.z) * scale * sin(Math.toRadians(30.0)).toFloat() - (p.y * scale)
    return Offset(isoX, isoY)
}

@Composable
fun RoomViewer2_5D(floorCorners: List<Point3D>, wallHeight: Float) {
    val walls = extrudeWalls(floorCorners, wallHeight)

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Fit the whole room in view: find the projected bounding box first,
        // then pick a scale + offset that centers it with some padding.
        val allPoints = floorCorners + floorCorners.map { Point3D(it.x, wallHeight, it.z) }
        val roughProjected = allPoints.map { isoProject(it, 1f) }
        val minX = roughProjected.minOf { it.x }
        val maxX = roughProjected.maxOf { it.x }
        val minY = roughProjected.minOf { it.y }
        val maxY = roughProjected.maxOf { it.y }

        val padding = 100f
        val scaleX = (size.width - padding * 2) / (maxX - minX).coerceAtLeast(0.01f)
        val scaleY = (size.height - padding * 2) / (maxY - minY).coerceAtLeast(0.01f)
        val scale = minOf(scaleX, scaleY)

        fun toScreen(p: Point3D): Offset {
            val projected = isoProject(p, scale)
            val centerX = (minX + maxX) / 2 * scale
            val centerY = (minY + maxY) / 2 * scale
            return Offset(
                size.width / 2 - centerX + projected.x,
                size.height / 2 - centerY + projected.y
            )
        }

        drawFloor(floorCorners, ::toScreen)
        drawWalls(walls, ::toScreen)
    }
}

private fun DrawScope.drawFloor(floorCorners: List<Point3D>, toScreen: (Point3D) -> Offset) {
    val path = Path()
    floorCorners.forEachIndexed { i, corner ->
        val screenPoint = toScreen(corner)
        if (i == 0) path.moveTo(screenPoint.x, screenPoint.y) else path.lineTo(screenPoint.x, screenPoint.y)
    }
    path.close()
    drawPath(path, color = Color(0xFFD8D0C0))
    drawPath(path, color = Color(0xFF8A8270), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
}

private fun DrawScope.drawWalls(walls: List<WallFace>, toScreen: (Point3D) -> Offset) {
    // Painter's algorithm: draw walls further from the viewer first, so
    // nearer walls correctly overlap them. Depth approximated by (x + z) —
    // good enough for a fixed isometric angle and a simple room shape.
    val sortedWalls = walls.sortedByDescending { it.floorA.x + it.floorA.z + it.floorB.x + it.floorB.z }

    sortedWalls.forEach { wall ->
        val path = Path().apply {
            val fa = toScreen(wall.floorA)
            val fb = toScreen(wall.floorB)
            val cb = toScreen(wall.ceilB)
            val ca = toScreen(wall.ceilA)
            moveTo(fa.x, fa.y)
            lineTo(fb.x, fb.y)
            lineTo(cb.x, cb.y)
            lineTo(ca.x, ca.y)
            close()
        }

        // Simple pseudo-lighting: walls running more "left-right" vs
        // "front-back" get a slightly different shade, just enough to make
        // the room read as 3D rather than a flat outline.
        val dx = wall.floorB.x - wall.floorA.x
        val dz = wall.floorB.z - wall.floorA.z
        val isMostlyXFacing = kotlin.math.abs(dx) > kotlin.math.abs(dz)
        val fillColor = if (isMostlyXFacing) Color(0xFFEDE7DA) else Color(0xFFC9C0AD)

        drawPath(path, color = fillColor)
        drawPath(path, color = Color(0xFF8A8270), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
    }
}
