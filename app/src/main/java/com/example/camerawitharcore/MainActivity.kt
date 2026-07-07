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
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.rememberOnGestureListener
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
 * A door or window cut into one wall. wallIndex refers to the wall between
 * finishedCorners[wallIndex] and finishedCorners[wallIndex + 1]. startOffset
 * and endOffset are distances (meters) along that wall from its first
 * corner. sillHeight/headerHeight are heights (meters) above the floor —
 * for a door, sillHeight is expected to be close to 0.
 */
data class Opening(
    val wallIndex: Int,
    val startOffset: Float,
    val endOffset: Float,
    val sillHeight: Float,
    val headerHeight: Float,
    val type: String // "door" or "window"
)

/**
 * An auto-detected candidate opening, not yet confirmed as a door or window
 * by the user. Comes from finding a gap in ARCore's vertical-plane coverage
 * along a wall — since a real opening (no wall surface, or glass) usually
 * prevents one continuous plane from forming across it.
 */
data class CandidateOpening(
    val wallIndex: Int,
    val startOffset: Float,
    val endOffset: Float,
    val estimatedSill: Float,
    val estimatedHeader: Float
)

/** A gap narrower than this is probably just noise/a small unscanned patch, not a real opening. */
private const val MIN_GAP_WIDTH_METERS = 0.4f

/** A gap wider than this is probably a whole missing wall segment, not a door/window. */
private const val MAX_GAP_WIDTH_METERS = 3.0f

/** How close a vertical plane's fragment must be to a wall's line to be considered part of that wall. */
private const val WALL_MATCH_DISTANCE_METERS = 0.5f

/** Coverage intervals closer together than this are merged as one continuous piece of wall. */
private const val COVERAGE_MERGE_TOLERANCE_METERS = 0.15f

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

private enum class TapMode { PLACING_CORNERS, CALIBRATING_HEIGHT, PLACING_OPENING }

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

    // --- Openings (doors/windows) state ---
    var finishedCorners by remember { mutableStateOf<List<RoomCorner>>(emptyList()) }
    val openings = remember { mutableStateListOf<Opening>() }
    var currentOpeningType by remember { mutableStateOf("door") }
    val openingTapPoints = remember { mutableStateListOf<Pose>() }
    var arSession by remember { mutableStateOf<Session?>(null) }
    val candidateOpenings = remember { mutableStateListOf<CandidateOpening>() }

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
                arSession = session
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
                onSingleTapConfirmed = { motionEvent, _ ->
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

                        TapMode.PLACING_OPENING -> {
                            // First two taps: bottom-left and bottom-right of the
                            // opening — prefer a floor hit for these since they're
                            // usually low, but fall back to any surface (a door
                            // sill is right at floor level, but a window's sill
                            // sits up on the wall itself with no floor plane there).
                            val floorHit = hitResults.firstOrNull {
                                val trackable = it.trackable
                                trackable is Plane &&
                                        trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                        trackable.isPoseInPolygon(it.hitPose)
                            }
                            val anyHit = floorHit ?: hitResults.firstOrNull()

                            if (anyHit == null) {
                                lastTapResult = "No surface detected there — try again"
                                return@rememberOnGestureListener
                            }

                            openingTapPoints.add(anyHit.hitPose)

                            when (openingTapPoints.size) {
                                1 -> lastTapResult = "Tap the OTHER bottom corner of the $currentOpeningType"
                                2 -> lastTapResult = "Now tap the TOP of the $currentOpeningType (header height)"
                                3 -> {
                                    val opening = buildOpening(
                                        openingTapPoints[0],
                                        openingTapPoints[1],
                                        openingTapPoints[2],
                                        finishedCorners,
                                        currentOpeningType
                                    )
                                    if (opening != null) {
                                        openings.add(opening)
                                        lastTapResult = "${currentOpeningType.replaceFirstChar { it.uppercase() }} added " +
                                                "on wall ${opening.wallIndex + 1} (width %.2f m)".format(opening.endOffset - opening.startOffset)
                                        saveRoomJson(filesDir, finishedCorners, roomHeight ?: 0f, heightSource, openings)
                                    } else {
                                        lastTapResult = "Couldn't match those taps to a wall — try again, closer to the wall"
                                    }
                                    openingTapPoints.clear()
                                    tapMode = TapMode.PLACING_CORNERS
                                }
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
            if (openings.isNotEmpty()) {
                Text(text = "Openings: ${openings.size}", color = Color.White)
            }
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
                        finishedCorners = simplified
                        summary = buildRoomSummary(simplified, roomHeight!!) +
                                if (simplified.size != corners.size)
                                    "\n(merged ${corners.size - simplified.size} redundant point(s) along straight walls)"
                                else ""
                        saveRoomJson(filesDir, simplified, roomHeight!!, heightSource, openings)
                    }
                ) { Text("Finish Room") }

                Button(
                    onClick = {
                        corners.clear()
                        roomFinished = false
                        finishedCorners = emptyList()
                        openings.clear()
                        candidateOpenings.clear()
                        openingTapPoints.clear()
                        tapMode = TapMode.PLACING_CORNERS
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
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            val session = arSession
                            if (session != null) {
                                candidateOpenings.clear()
                                candidateOpenings.addAll(scanForOpenings(session, finishedCorners))
                                lastTapResult = if (candidateOpenings.isEmpty())
                                    "No gaps found — scan walls more, or use Add Door/Window manually"
                                else
                                    "${candidateOpenings.size} possible opening(s) found — confirm below"
                            }
                        }
                    ) { Text("Auto-Detect Openings") }
                }

                candidateOpenings.forEachIndexed { index, candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            text = "Wall ${candidate.wallIndex + 1}, %.2f m wide".format(candidate.endOffset - candidate.startOffset),
                            color = Color.White
                        )
                        Button(onClick = {
                            openings.add(
                                Opening(
                                    candidate.wallIndex, candidate.startOffset, candidate.endOffset,
                                    candidate.estimatedSill, candidate.estimatedHeader, "door"
                                )
                            )
                            candidateOpenings.removeAt(index)
                            saveRoomJson(filesDir, finishedCorners, roomHeight ?: 0f, heightSource, openings)
                        }) { Text("Door") }
                        Button(onClick = {
                            openings.add(
                                Opening(
                                    candidate.wallIndex, candidate.startOffset, candidate.endOffset,
                                    candidate.estimatedSill, candidate.estimatedHeader, "window"
                                )
                            )
                            candidateOpenings.removeAt(index)
                            saveRoomJson(filesDir, finishedCorners, roomHeight ?: 0f, heightSource, openings)
                        }) { Text("Window") }
                        Button(onClick = { candidateOpenings.removeAt(index) }) { Text("Ignore") }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            currentOpeningType = "door"
                            openingTapPoints.clear()
                            tapMode = TapMode.PLACING_OPENING
                            lastTapResult = "Tap one bottom corner of the door"
                        }
                    ) { Text("Add Door") }

                    Button(
                        onClick = {
                            currentOpeningType = "window"
                            openingTapPoints.clear()
                            tapMode = TapMode.PLACING_OPENING
                            lastTapResult = "Tap one bottom corner of the window"
                        }
                    ) { Text("Add Window") }
                }

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

/**
 * Scans all currently-tracked vertical planes and looks for gaps in wall
 * coverage — places where a wall's line has no plane, or a break between
 * two separate plane fragments. A real door or window usually causes
 * exactly this pattern, since ARCore can't form one continuous surface
 * across an opening (no wall there, or glass with too few feature points).
 *
 * This is a heuristic, not true object recognition — it can miss openings
 * that haven't been scanned enough, and can occasionally flag a large
 * unscanned patch of blank wall as a false positive. That's why results
 * come back as CandidateOpenings for the user to confirm/classify, not
 * openings added directly.
 */
private fun scanForOpenings(session: Session, finishedCorners: List<RoomCorner>): List<CandidateOpening> {
    if (finishedCorners.size < 3) return emptyList()

    val verticalPlanes = session.getAllTrackables(Plane::class.java)
        .filter { it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING }

    val floorRefY = finishedCorners.map { it.y }.average().toFloat()
    val candidates = mutableListOf<CandidateOpening>()

    data class Interval(val start: Float, val end: Float, val yMin: Float, val yMax: Float)

    for (wallIdx in finishedCorners.indices) {
        val a = finishedCorners[wallIdx]
        val b = finishedCorners[(wallIdx + 1) % finishedCorners.size]
        val wallLength = distance(a, b)
        if (wallLength < 0.3f) continue

        val intervals = mutableListOf<Interval>()

        verticalPlanes.forEach { plane ->
            val polygon = plane.polygon
            polygon.rewind()
            val worldPoints = mutableListOf<FloatArray>()
            while (polygon.hasRemaining()) {
                val lx = polygon.get()
                val lz = polygon.get()
                worldPoints.add(plane.centerPose.transformPoint(floatArrayOf(lx, 0f, lz)))
            }
            if (worldPoints.isEmpty()) return@forEach

            val nearWall = worldPoints.any {
                pointToSegmentDistance(it[0], it[2], a.x, a.z, b.x, b.z) < WALL_MATCH_DISTANCE_METERS
            }
            if (!nearWall) return@forEach

            val offsets = worldPoints.map { projectOntoSegment(it[0], it[2], a.x, a.z, b.x, b.z) * wallLength }
            val ys = worldPoints.map { it[1] - floorRefY }
            intervals.add(Interval(offsets.min(), offsets.max(), ys.min(), ys.max()))
        }

        if (intervals.isEmpty()) continue

        // Merge overlapping/near-touching plane fragments into continuous coverage
        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf<Interval>()
        sorted.forEach { iv ->
            val last = merged.lastOrNull()
            if (last != null && iv.start - last.end < COVERAGE_MERGE_TOLERANCE_METERS) {
                merged[merged.size - 1] = Interval(
                    last.start, maxOf(last.end, iv.end),
                    minOf(last.yMin, iv.yMin), maxOf(last.yMax, iv.yMax)
                )
            } else {
                merged.add(iv)
            }
        }

        // Walk the merged coverage looking for gaps within [0, wallLength]
        var cursor = 0f
        var previous: Interval? = null
        for (iv in merged) {
            val gapWidth = iv.start - cursor
            if (gapWidth in MIN_GAP_WIDTH_METERS..MAX_GAP_WIDTH_METERS) {
                candidates.add(
                    CandidateOpening(
                        wallIndex = wallIdx,
                        startOffset = cursor,
                        endOffset = iv.start,
                        // If there's wall coverage below this gap, its top is
                        // a good estimate for the opening's sill (a window
                        // sitting above solid wall). Otherwise assume it
                        // reaches the floor, like a door.
                        estimatedSill = previous?.yMax?.coerceAtLeast(0f) ?: 0f,
                        estimatedHeader = iv.yMax.coerceIn(1.0f, 3.0f)
                    )
                )
            }
            cursor = maxOf(cursor, iv.end)
            previous = iv
        }
        val trailingGap = wallLength - cursor
        if (trailingGap in MIN_GAP_WIDTH_METERS..MAX_GAP_WIDTH_METERS) {
            candidates.add(
                CandidateOpening(
                    wallIndex = wallIdx,
                    startOffset = cursor,
                    endOffset = wallLength,
                    estimatedSill = previous?.yMax?.coerceAtLeast(0f) ?: 0f,
                    estimatedHeader = previous?.yMax?.coerceIn(1.0f, 3.0f) ?: 2.0f
                )
            )
        }
    }

    return candidates
}

/**
 * Given two taps marking the bottom-left/bottom-right of an opening and a
 * third tap marking its top, finds which wall of the finished room polygon
 * the opening belongs to, and computes its offset along that wall plus its
 * sill/header heights above the floor.
 *
 * Returns null if the taps don't land close enough to any wall to be
 * confidently assigned — the caller should ask the user to try again.
 */
private fun buildOpening(
    bottomA: Pose,
    bottomB: Pose,
    top: Pose,
    finishedCorners: List<RoomCorner>,
    type: String
): Opening? {
    if (finishedCorners.size < 3) return null

    val midX = (bottomA.tx() + bottomB.tx()) / 2f
    val midZ = (bottomA.tz() + bottomB.tz()) / 2f

    // Find the nearest wall segment (edge of the polygon) to the midpoint
    // of the two bottom taps — this is "which wall is this opening on".
    var bestWallIndex = -1
    var bestDistance = Float.MAX_VALUE

    for (i in finishedCorners.indices) {
        val a = finishedCorners[i]
        val b = finishedCorners[(i + 1) % finishedCorners.size]
        val dist = pointToSegmentDistance(midX, midZ, a.x, a.z, b.x, b.z)
        if (dist < bestDistance) {
            bestDistance = dist
            bestWallIndex = i
        }
    }

    // If the taps are more than ~1m from every wall, something's off —
    // don't silently assign a wrong wall.
    if (bestWallIndex == -1 || bestDistance > 1.0f) return null

    val wallA = finishedCorners[bestWallIndex]
    val wallB = finishedCorners[(bestWallIndex + 1) % finishedCorners.size]
    val wallLength = distance(wallA, wallB)
    if (wallLength < 0.01f) return null

    val offsetA = projectOntoSegment(bottomA.tx(), bottomA.tz(), wallA.x, wallA.z, wallB.x, wallB.z) * wallLength
    val offsetB = projectOntoSegment(bottomB.tx(), bottomB.tz(), wallA.x, wallA.z, wallB.x, wallB.z) * wallLength

    val floorRefY = finishedCorners.map { it.y }.average().toFloat()
    val sillHeight = ((bottomA.ty() + bottomB.ty()) / 2f) - floorRefY
    val headerHeight = top.ty() - floorRefY

    if (headerHeight <= sillHeight) return null // top tap was below the bottom taps — bad capture

    return Opening(
        wallIndex = bestWallIndex,
        startOffset = minOf(offsetA, offsetB),
        endOffset = maxOf(offsetA, offsetB),
        sillHeight = sillHeight.coerceAtLeast(0f),
        headerHeight = headerHeight,
        type = type
    )
}

/** Shortest distance from point (px, pz) to the line segment (ax,az)-(bx,bz). */
private fun pointToSegmentDistance(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
    val t = projectOntoSegment(px, pz, ax, az, bx, bz).coerceIn(0f, 1f)
    val closestX = ax + (bx - ax) * t
    val closestZ = az + (bz - az) * t
    val dx = px - closestX
    val dz = pz - closestZ
    return sqrt(dx * dx + dz * dz)
}

/** Returns how far along segment (ax,az)-(bx,bz) the point (px,pz) projects to, as a 0..1 fraction (unclamped). */
private fun projectOntoSegment(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
    val dx = bx - ax
    val dz = bz - az
    val lengthSquared = dx * dx + dz * dz
    if (lengthSquared < 0.0001f) return 0f
    return ((px - ax) * dx + (pz - az) * dz) / lengthSquared
}


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

/** Saves the finished room outline + height + openings as room.json in internal storage. */
private fun saveRoomJson(
    filesDir: File,
    corners: List<RoomCorner>,
    height: Float,
    heightSource: String,
    openings: List<Opening>
) {
    val pointsArray = JSONArray()
    corners.forEach { c ->
        val point = JSONObject()
        point.put("x", c.x)
        point.put("y", c.y)
        point.put("z", c.z)
        pointsArray.put(point)
    }

    val openingsArray = JSONArray()
    openings.forEach { o ->
        val openingJson = JSONObject()
        openingJson.put("wallIndex", o.wallIndex)
        openingJson.put("startOffset", o.startOffset)
        openingJson.put("endOffset", o.endOffset)
        openingJson.put("sillHeight", o.sillHeight)
        openingJson.put("headerHeight", o.headerHeight)
        openingJson.put("type", o.type)
        openingsArray.put(openingJson)
    }

    val root = JSONObject()
    root.put("points", pointsArray)
    root.put("wallHeight", height)
    root.put("heightSource", heightSource)
    root.put("openings", openingsArray)

    val file = File(filesDir, "room.json")
    file.writeText(root.toString(2))
    Log.d("ARTest", "Saved room.json -> ${file.absolutePath}")
    Log.d("ARTest", root.toString(2))
}
