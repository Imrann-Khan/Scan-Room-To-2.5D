package com.example.camerawitharcore

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
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** A single room corner captured from a floor tap, in meters, ARCore world space. */
data class RoomCorner(val x: Float, val y: Float, val z: Float)

/**
 * Step 3 of the build: corner capture.
 *
 * Every tap on the FLOOR (not walls — see the occlusion discussion earlier,
 * floor taps are far more reliable) is stored as a room corner. You can:
 *  - Undo the last corner if you mis-tap
 *  - Finish the room, which closes the loop back to the first corner and
 *    prints each wall segment's length + the total perimeter
 *  - The finished polygon is saved as room.json in the app's internal
 *    storage, ready for the next step (polygon simplification + wall
 *    extrusion).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RoomScanScreen(filesDir = filesDir)
        }
    }
}

@Composable
fun RoomScanScreen(filesDir: File) {
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
            },

            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, _ ->
                    if (roomFinished) return@rememberOnGestureListener
                    val currentFrame = frame ?: return@rememberOnGestureListener
                    val hitResults = currentFrame.hitTest(motionEvent.x, motionEvent.y)

                    // Only accept hits on the FLOOR (horizontal upward-facing plane).
                    // This is deliberate — floor taps are far more reliable than wall
                    // taps, especially with furniture occluding walls (see earlier
                    // discussion). We derive the room outline from floor corners.
                    val floorHit = hitResults.firstOrNull {
                        val trackable = it.trackable
                        trackable is Plane &&
                                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                trackable.isPoseInPolygon(it.hitPose)
                    }

                    if (floorHit != null) {
                        val p = floorHit.hitPose
                        corners.add(RoomCorner(p.tx(), p.ty(), p.tz()))
                        lastTapResult = "Corner ${corners.size}: x=%.2f z=%.2f".format(p.tx(), p.tz())
                        Log.d("ARTest", "Corner added -> $lastTapResult")
                    } else {
                        lastTapResult = "Miss: aim at the floor, not a wall"
                        Log.d("ARTest", "TAP MISS -> no floor plane at that point yet")
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
            Text(text = lastTapResult, color = Color.Yellow)
            if (summary.isNotEmpty()) {
                Text(text = summary, color = Color.Cyan)
            }
        }

        // Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.4f)),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (corners.isNotEmpty() && !roomFinished) {
                        corners.removeAt(corners.size - 1)
                        lastTapResult = "Removed last corner (${corners.size} left)"
                    }
                }
            ) {
                Text("Undo")
            }

            Button(
                onClick = {
                    if (corners.size < 3) {
                        summary = "Need at least 3 corners to close a room"
                        return@Button
                    }
                    roomFinished = true
                    summary = buildRoomSummary(corners)
                    saveRoomJson(filesDir, corners)
                }
            ) {
                Text("Finish Room")
            }

            Button(
                onClick = {
                    corners.clear()
                    roomFinished = false
                    summary = ""
                    lastTapResult = "Cleared. Tap the floor to place a corner"
                }
            ) {
                Text("Reset")
            }
        }
    }
}

/** Closes the polygon (last corner back to first) and reports each wall's length. */
private fun buildRoomSummary(corners: List<RoomCorner>): String {
    val lines = StringBuilder()
    var perimeter = 0f
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size] // wraps around to close the loop
        val length = distance(a, b)
        perimeter += length
        lines.append("Wall ${i + 1}: %.2f m\n".format(length))
    }
    lines.append("Perimeter: %.2f m".format(perimeter))
    return lines.toString()
}

private fun distance(a: RoomCorner, b: RoomCorner): Float {
    val dx = a.x - b.x
    val dz = a.z - b.z
    return sqrt(dx * dx + dz * dz)
}

/** Saves the finished room outline as room.json in internal storage. */
private fun saveRoomJson(filesDir: File, corners: List<RoomCorner>) {
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

    val file = File(filesDir, "room.json")
    file.writeText(root.toString(2))
    Log.d("ARTest", "Saved room.json -> ${file.absolutePath}")
    Log.d("ARTest", root.toString(2))
}
