package com.example.camerawitharcore

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** A single room corner captured from a floor tap, in meters, ARCore world space. */
data class RoomCorner(val x: Float, val y: Float, val z: Float)

/**
 * Step 4: automatic room-height detection.
 *
 * Height comes from one of two sources, in priority order:
 *  1. AUTO — once ARCore detects both a floor plane (HORIZONTAL_UPWARD_FACING)
 *     and a ceiling plane (HORIZONTAL_DOWNWARD_FACING), height is just the
 *     vertical gap between them. Requires pointing the phone at the ceiling
 *     briefly during scanning.
 *  2. MANUAL TAP CALIBRATION — fallback for when the ceiling won't detect
 *     (blank/low-texture ceilings are as hard as blank walls). The user
 *     taps two points on any clearly visible vertical edge — a doorframe,
 *     a corner, a light switch column — one low and one high. The distance
 *     between those two real 3D points becomes the height. No typed number
 *     needed either way.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RoomScanScreen(filesDir = filesDir)
        }
    }
}

private enum class TapMode { PLACING_CORNERS, CALIBRATING_HEIGHT }

/** Taps closer together than this (meters) are treated as accidental duplicates. */
private const val MIN_CORNER_SPACING_METERS = 0.15f

/** Points within this angle (degrees) of a straight line are merged as collinear. */
private const val COLLINEAR_ANGLE_TOLERANCE_DEGREES = 8.0

@Composable
fun RoomScanScreen(filesDir: File) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    var frame by remember { mutableStateOf<Frame?>(null) }
    var horizontalCount by remember { mutableStateOf(0) }
    var verticalCount by remember { mutableStateOf(0) }
    var lastTapResult by remember { mutableStateOf("Tap the floor to place a corner") }
    val corners = remember { mutableStateListOf<RoomCorner>() }
    var roomFinished by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("") }

    // --- Height detection state ---
    var roomHeight by remember { mutableStateOf<Float?>(null) }
    var heightSource by remember { mutableStateOf("not detected yet") }
    var tapMode by remember { mutableStateOf(TapMode.PLACING_CORNERS) }
    var calibrationFirstPoint by remember { mutableStateOf<Pose?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {

        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraStream = rememberARCameraStream(materialLoader),
            planeRenderer = true,

            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            },

            onSessionUpdated = { session, updatedFrame ->
                frame = updatedFrame
                val allPlanes = session.getAllTrackables(Plane::class.java)
                verticalCount = allPlanes.count { it.type == Plane.Type.VERTICAL }
                horizontalCount = allPlanes.size - verticalCount

                // Auto height detection — only runs until a height is found,
                // and never overrides a manual calibration once one is set.
                if (roomHeight == null) {
                    val floorPlane = allPlanes.firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    val ceilingPlane = allPlanes.firstOrNull { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }
                    if (floorPlane != null && ceilingPlane != null) {
                        val height = abs(ceilingPlane.centerPose.ty() - floorPlane.centerPose.ty())
                        // Sanity check — reject obviously wrong detections
                        // (e.g. a shelf mistaken for a ceiling).
                        if (height in 1.8f..4.5f) {
                            roomHeight = height
                            heightSource = "auto (ceiling detected)"
                            Log.d("ARTest", "Auto-detected room height: $height m")
                        }
                    }
                }
            },

            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent: android.view.MotionEvent, _ ->
                    val currentFrame = frame ?: return@rememberOnGestureListener
                    val hitResults = currentFrame.hitTest(motionEvent.x, motionEvent.y)

                    when (tapMode) {
                        TapMode.PLACING_CORNERS -> {
                            if (roomFinished) return@rememberOnGestureListener

                            val floorHit = hitResults.firstOrNull {
                                val trackable = it.trackable
                                trackable is Plane &&
                                        trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                        trackable.isPoseInPolygon(it.hitPose)
                            }

                            if (floorHit != null) {
                                val p = floorHit.hitPose
                                val newCorner = RoomCorner(p.tx(), p.ty(), p.tz())

                                val tooClose = corners.lastOrNull()?.let { last ->
                                    distance(last, newCorner) < MIN_CORNER_SPACING_METERS
                                } ?: false

                                if (tooClose) {
                                    lastTapResult = "Too close to the last corner (%.2f m) — move further away or tap Undo"
                                        .format(MIN_CORNER_SPACING_METERS)
                                } else {
                                    corners.add(newCorner)
                                    lastTapResult = "Corner ${corners.size}: x=%.2f z=%.2f".format(p.tx(), p.tz())
                                }
                            } else {
                                lastTapResult = "Miss: aim at the floor, not a wall"
                            }
                        }

                        TapMode.CALIBRATING_HEIGHT -> {
                            // Accept a hit on any plane or tracked point — this is a
                            // deliberately loose hit-test since we're relying on the
                            // user to pick a well-textured vertical edge themselves.
                            val anyHit = hitResults.firstOrNull()
                            if (anyHit == null) {
                                lastTapResult = "No surface detected there — try a doorframe or corner"
                                return@rememberOnGestureListener
                            }

                            val first = calibrationFirstPoint
                            if (first == null) {
                                calibrationFirstPoint = anyHit.hitPose
                                lastTapResult = "First point set — now tap directly above it, near the ceiling"
                            } else {
                                val second = anyHit.hitPose
                                val dx = first.tx() - second.tx()
                                val dy = first.ty() - second.ty()
                                val dz = first.tz() - second.tz()
                                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                                roomHeight = dist
                                heightSource = "manual tap calibration"
                                calibrationFirstPoint = null
                                tapMode = TapMode.PLACING_CORNERS
                                lastTapResult = "Height calibrated: %.2f m".format(dist)
                                Log.d("ARTest", "Manually calibrated height: $dist m")
                            }
                        }
                    }
                }
            )
        )

        // Status overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(text = "Floor planes: $horizontalCount", color = Color.Cyan)
            Text(text = "Wall planes: $verticalCount", color = Color.Green)
            Text(text = "Corners placed: ${corners.size}", color = Color.White)
            val heightText = roomHeight?.let { "Room height: %.2f m ($heightSource)".format(it) }
                ?: "Room height: $heightSource — point phone at ceiling"
            Text(text = heightText, color = Color.Magenta)
            Text(text = lastTapResult, color = Color.Yellow)
            if (summary.isNotEmpty()) {
                Text(text = summary, color = Color.Cyan)
            }
        }

        // Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        if (corners.isNotEmpty() && !roomFinished) {
                            corners.removeAt(corners.size - 1)
                            lastTapResult = "Removed last corner (${corners.size} left)"
                        }
                    }
                ) { Text("Undo") }

                Button(
                    onClick = {
                        if (corners.size < 3) {
                            summary = "Need at least 3 corners to close a room"
                            return@Button
                        }
                        if (roomHeight == null) {
                            summary = "No height yet — wait for auto-detect or use Calibrate Height"
                            return@Button
                        }
                        val simplified = simplifyCollinearPoints(corners)
                        roomFinished = true
                        summary = buildRoomSummary(simplified, roomHeight!!) +
                                if (simplified.size != corners.size)
                                    "\n(merged ${corners.size - simplified.size} redundant point(s) along straight walls)"
                                else ""
                        saveRoomJson(filesDir, simplified, roomHeight!!, heightSource)
                    }
                ) { Text("Finish Room") }

                Button(
                    onClick = {
                        corners.clear()
                        roomFinished = false
                        summary = ""
                        lastTapResult = "Cleared. Tap the floor to place a corner"
                    }
                ) { Text("Reset") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        if (roomHeight != null) {
                            // Allow re-calibrating even after an auto value was found
                            roomHeight = null
                        }
                        calibrationFirstPoint = null
                        tapMode = TapMode.CALIBRATING_HEIGHT
                        lastTapResult = "Tap a point low on a doorframe/corner, near the floor"
                    }
                ) { Text("Calibrate Height Manually") }
            }

            if (roomFinished) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, RoomViewerActivity::class.java))
                        }
                    ) { Text("View 2.5D Model") }
                }
            }
        }
    }
}

/**
 * Removes points that sit almost exactly on the straight line between their
 * neighbors — the result of tapping multiple spots along the same wall
 * instead of just at the corners. Only the points where the wall actually
 * changes direction (real corners) survive.
 */
private fun simplifyCollinearPoints(points: List<RoomCorner>): List<RoomCorner> {
    if (points.size <= 3) return points // a triangle or fewer can't have a redundant point

    val result = points.toMutableList()
    var changed = true

    while (changed && result.size > 3) {
        changed = false
        var i = 0
        while (i < result.size) {
            val prev = result[(i - 1 + result.size) % result.size]
            val curr = result[i]
            val next = result[(i + 1) % result.size]

            val angle1 = atan2(curr.z - prev.z, curr.x - prev.x)
            val angle2 = atan2(next.z - curr.z, next.x - curr.x)
            var deltaDeg = Math.toDegrees((angle2 - angle1).toDouble())
            // normalize to [-180, 180]
            while (deltaDeg > 180) deltaDeg -= 360
            while (deltaDeg < -180) deltaDeg += 360

            if (abs(deltaDeg) < COLLINEAR_ANGLE_TOLERANCE_DEGREES) {
                result.removeAt(i)
                changed = true
                // don't advance i — re-check the new point now at this index
            } else {
                i++
            }
        }
    }
    return result
}

/** Closes the polygon (last corner back to first) and reports each wall's length. */
private fun buildRoomSummary(corners: List<RoomCorner>, height: Float): String {
    val lines = StringBuilder()
    var perimeter = 0f
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size] // wraps around to close the loop
        val length = distance(a, b)
        perimeter += length
        lines.append("Wall ${i + 1}: %.2f m\n".format(length))
    }
    lines.append("Perimeter: %.2f m\n".format(perimeter))
    lines.append("Height: %.2f m".format(height))
    return lines.toString()
}

private fun distance(a: RoomCorner, b: RoomCorner): Float {
    val dx = a.x - b.x
    val dz = a.z - b.z
    return sqrt(dx * dx + dz * dz)
}

/** Saves the finished room outline + height as room.json in internal storage. */
private fun saveRoomJson(filesDir: File, corners: List<RoomCorner>, height: Float, heightSource: String) {
    val pointsArray = JSONArray()
    corners.forEach { c ->
        val point = JSONObject()
        point.put("x", c.x)
        point.put("y", c.y)
        point.put("z", c.z)
        pointsArray.put(point)
    }
    val root = JSONObject()
    root.put("points", pointsArray)
    root.put("wallHeight", height)
    root.put("heightSource", heightSource)

    val file = File(filesDir, "room.json")
    file.writeText(root.toString(2))
    Log.d("ARTest", "Saved room.json -> ${file.absolutePath}")
    Log.d("ARTest", root.toString(2))
}
