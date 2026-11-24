# Glass counting engine V3 — validation plan

## Status
Experimental branch only. V3 is not connected to the production screen and must not replace the current engine until it passes the reference set.

## Why the current engine is insufficient
The current engine treats a set of supported horizontal edge peaks as glass boundaries. Reflections, background objects, and repeated structures can also create peaks. Counting every accepted peak interval therefore overcounts or undercounts.

## V3 hypothesis
1. Divide the usable image width into independent vertical bands.
2. Build a horizontal edge-energy profile in each band.
3. Normalize each profile robustly and combine bands by a per-row median so a reflection limited to one band has less influence.
4. Search candidate vertical pitches using normalized autocorrelation.
5. Find the phase with strongest repeated boundary evidence.
6. Return a candidate sequence and confidence diagnostics, not an unconditional industrial count.

## Known limitations
- The prototype still assumes boundaries are approximately horizontal in image coordinates.
- Periodic reflections can also produce a strong autocorrelation peak.
- Staggered/in-out panels, strong perspective, tilted stacks, gaps, and missing boundaries need explicit validation and likely local geometric tracking.
- Thickness in millimetres cannot be converted to pixels without a scale/camera calibration. The user-entered thickness must not be treated as a pixel pitch by itself.
- The current confidence is a heuristic, not a calibrated probability.

## Required reference validation
The user has supplied known-count examples in earlier conversations:
- 27 panels, nominal thickness 10 mm
- 64 panels, nominal thickness 5 mm
- 99 panels
- 127 panels, nominal thickness 4 mm

For every image record: true count, predicted count, absolute error, percentage error, detected boundary overlay, and failure reason. Do not claim industrial readiness unless all reference images are available to the test harness and results meet an agreed tolerance.

## Acceptance gates
1. Kotlin compilation succeeds.
2. Synthetic unit tests cover clean periodic boundaries, missing boundaries, isolated reflections, non-periodic clutter, and pitch harmonics.
3. The four real reference images are run unchanged.
4. The model is evaluated on images not used to tune thresholds.
5. Only after passing these gates should V3 be integrated into the app's result screen.
