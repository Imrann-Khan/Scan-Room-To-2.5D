# Step 3: Corner capture

This builds directly on your working camera-feed + plane-detection app.
Instead of just logging tap coordinates, each floor tap now becomes a
stored **room corner**, and you get three on-screen controls to manage the
capture session.

## What's new

- **Tap the floor** to place a corner. Deliberately restricted to
  `Plane.Type.HORIZONTAL_UPWARD_FACING` (the floor) — not walls — because
  floor taps are far more reliable, especially with furniture occluding
  walls, as discussed earlier. Tapping a wall now shows "aim at the floor"
  instead of silently doing nothing.
- **Undo** — removes the most recently placed corner, for mis-taps.
- **Finish Room** — requires at least 3 corners. Closes the loop (connects
  the last corner back to the first), computes each wall segment's length
  and the total perimeter, and shows it on screen. This also **saves the
  polygon to internal storage** as `room.json`.
- **Reset** — clears everything and starts over.

## How to test it

1. Build and run on your device, same as before.
2. Walk to each corner of a room and tap the floor there, in order around
   the room (clockwise or counter-clockwise, doesn't matter, just
   consistent).
3. Watch **"Corners placed: N"** climb in the top-left overlay as you go.
4. Once you've tapped all corners, hit **Finish Room**. You should see
   something like:
   ```
   Wall 1: 3.42 m
   Wall 2: 2.85 m
   Wall 3: 3.40 m
   Wall 4: 2.90 m
   Perimeter: 12.57 m
   ```
   Compare these numbers to a rough tape-measure check of the real room —
   this is your first real accuracy test of the whole pipeline.

## Where room.json ends up

Internal app storage isn't visible in a normal file browser, but you can
pull it off the device to inspect it:

```
adb shell run-as com.example.camerawitharcore cat files/room.json
```

Or just check Logcat (tag `ARTest`) right after tapping Finish Room — the
full JSON is printed there too, e.g.:

```json
{
  "points": [
    { "x": 0.12, "y": -0.85, "z": 0.34 },
    { "x": 3.54, "y": -0.85, "z": 0.31 },
    { "x": 3.49, "y": -0.85, "z": 3.71 },
    { "x": 0.08, "y": -0.85, "z": 3.68 }
  ]
}
```

Note `y` will be roughly constant across all corners (it's floor height,
not room width) — `x` and `z` are your actual floor-plan coordinates.

## Known rough edges at this stage

- No visual marker is placed at each tapped corner yet (no dot/pin in the
  camera view) — you're flying a bit blind on exactly where each corner
  landed until you check the numbers. Adding a visible marker is a good
  next polish item, but isn't required to keep building.
- If you tap the same spot twice, both taps get recorded as separate
  corners — no dedup logic yet.

## Next step

Once `room.json` is saving sensible values that roughly match reality,
we're ready for **Step 4: polygon simplification + wall extrusion** —
taking these raw corner taps and turning them into clean 90°-snapped wall
segments with real height, ready for the 2.5D isometric render.
