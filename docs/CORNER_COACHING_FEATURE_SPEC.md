# Corner-Centric Coaching Feature Spec (v1)

**Product:** Karting Tracker (Android, IMU-only).  
**Spec version:** 1.0 (implementation-targeted).  
**Date:** 2026-04-02.

---

## 1) Feature Goal

Enable drivers to leave each session with **immediate, corner-specific coaching** that is actionable for the next run.

The feature must convert existing telemetry and analysis outputs (lap/sector/peak/comparison/track-layout/profile/coach pipeline) into guidance like:

- "Brake earlier into Corner 2."
- "Corner 3 is already very consistent."
- "Brake slightly later into Corner 4."
- "Trail braking in Corner 5 looks promising."
- "Focus on exit speed in Corner 6."

Primary success outcome:

- After session processing, the app surfaces **Top 3 next actions** and a **per-corner coaching summary** with confidence labels.

---

## 2) Scope

### In scope (v1)

1. Deterministic corner mapping of lap-derived events to stable corner indices (`Corner 1..N`) using existing normalized-lap and track-layout/profile assets.
2. Per-corner metric extraction from current IMU-derived signals only (`longitudinalAccel`, `lateralAccel`, `totalAcceleration`, `yawRateAbs`) plus existing peaks, sectors, and lap confidence.
3. Reference model selection hierarchy (session best / session robust median / track profile context).
4. Explainable rule engine generating short human-readable coaching statements with confidence.
5. New UI outputs:
   - start-page compact coaching nutshell (Top 3 actions)
   - detailed corner coaching screen (all corners)
6. Pragmatic persistence of stable derived corner insight payload in `Session` JSON.

### Out of scope (v1)

1. Live real-time corner coaching during recording.
2. GPS-based racing line, true speed, slip-angle, or absolute position reconstruction.
3. ML/AI black-box model recommendations.
4. Automatic track map editing or new sensor fusion pipeline.
5. Cross-session adaptive strategy optimization beyond lightweight historical reference fallback.
6. Voice coaching or wearable integrations.

---

## 3) User-Facing Outcome

### Post-session compact summary (start/main surface)

Show a small card: **"Corner Coaching (Latest Session)"** with:

- Top 3 actionable items for next run.
- Biggest time-gain corner.
- Most consistent corner (positive reinforcement).
- Most inconsistent corner (focus warning).
- Overall coaching confidence badge (`High`, `Medium`, `Low`).

Example compact output:

- `1) Corner 2: Brake ~3% lap earlier for cleaner entry.`
- `2) Corner 6: Prioritize exit acceleration.`
- `3) Corner 5: Trail-brake overlap correlates with better split.`
- `Strongest: Corner 3 (very consistent).`
- `Largest opportunity: Corner 6 (+0.18s est).`

### Detailed corner coaching view

Per corner (expandable row/card):

- Corner label (`Corner 4`, mapped name if available from layout).
- Status chips: `Opportunity`, `Strong`, `Inconsistent`, `Low confidence`.
- Main coaching sentence (1 line).
- Supporting evidence bullets:
  - estimated local time delta
  - brake timing trend vs reference
  - exit proxy trend vs reference
  - consistency score + sample count
- confidence bar and reason tooltip (e.g., "limited clean laps").

Optional secondary tab/section:

- sorted list by estimated gain potential.

---

## 4) Required Domain Concepts

Introduce new domain/data models (Kotlin naming aligned to existing style).

### `CornerWindow`
Purpose: Stable normalized lap range for one detected/layout corner.

Fields:

- `cornerIndex: Int` (1-based display index)
- `startPercent: Float`
- `apexPercent: Float`
- `endPercent: Float`
- `mappingConfidence: Float` (0..1)
- `source: CornerWindowSource` (`DETECTED`, `LAYOUT_ALIGNED`, `SECTOR_FALLBACK`)

### `CornerPerformance`
Purpose: Per-lap/per-corner extracted metric bundle.

Fields:

- `lapId: Int`
- `cornerIndex: Int`
- `lapConfidence: Float`
- `isUsable: Boolean`
- `brakeStartPercent: Float?`
- `brakePeakPercent: Float?`
- `brakeDurationPercent: Float?`
- `entryDecelMean: Float?`
- `rotationPeak: Float?` (from `yawRateAbs`/`lateralAccel` proxy)
- `midCornerStability: Float?` (variance proxy)
- `exitAccelMean: Float?`
- `localTimeMs: Long?`
- `signalQuality: Float` (0..1)

### `CornerReference`
Purpose: Robust comparison target for a corner.

Fields:

- `cornerIndex: Int`
- `referenceType: CornerReferenceType` (`ROBUST_SESSION`, `BEST_SESSION_LAP`, `TRACK_PROFILE_PRIOR`, `FALLBACK`)
- `sampleCount: Int`
- `metrics: CornerReferenceMetrics`
- `qualityScore: Float`

### `CornerConsistency`
Purpose: Reliability and repeatability per corner.

Fields:

- `cornerIndex: Int`
- `usableLapCount: Int`
- `brakePointStdDevPercent: Float?`
- `exitAccelStdDev: Float?`
- `localTimeStdDevMs: Float?`
- `consistencyScore: Float` (0..1, higher better)

### `CornerTechniqueSignal`
Purpose: Intermediate deterministic technique flags.

Fields:

- `cornerIndex: Int`
- `trailBrakeOverlapScore: Float`
- `lateBrakePatternScore: Float`
- `earlyBrakePatternScore: Float`
- `exitCompromiseScore: Float`
- `stabilityRiskScore: Float`

### `CornerCoachingInsight`
Purpose: Final human-facing statement + metadata.

Fields:

- `cornerIndex: Int`
- `cornerLabel: String`
- `category: CornerInsightCategory` (`ACTION`, `POSITIVE`, `CONSISTENCY`, `CAUTION`)
- `headline: String`
- `details: String?`
- `estimatedGainMs: Float?`
- `confidence: Float` (0..1)
- `evidence: List<String>`
- `ruleId: String` (traceability/debug)

### `CornerCoachingSummary`
Purpose: Session-level aggregation for UI surfaces.

Fields:

- `topActions: List<CornerCoachingInsight>`
- `strongestCorner: CornerCoachingInsight?`
- `mostInconsistentCorner: CornerCoachingInsight?`
- `biggestOpportunityCorner: CornerCoachingInsight?`
- `overallConfidence: Float`

---

## 5) Mapping Existing Data to Corners

### Inputs reused

- `TrackLayout.detectedCorners` / manual `corners`
- `TrackLayoutMapper` corner reference utilities
- lap normalization (`LapNormalizer.DEFAULT_POINT_COUNT`)
- sector boundaries/times
- braking + cornering peaks (`PeakDetector` outputs already stored in `Lap`)
- lap phase/confidence/disturbed flags
- optional `TrackProfile.typicalSectorBoundaries`, zone priors

### Mapping strategy (deterministic)

1. **Build canonical corner windows** from detected corners on track layout if available:
   - apex = detected corner `peakPercent`
   - start/end from neighbor midpoints (circular), padded ± configurable margin.
2. If detected layout corners unavailable, derive windows from **reference lap cornering peaks** on normalized axis.
3. If peaks are sparse/weak, fallback to **sector-based pseudo-corners** (one main turn opportunity per sector region).
4. Use circular normalized percent distance to align each lap event (brake start, brake peak, yaw peak, cornering peak) to nearest corner window.
5. Require minimal event density + distance threshold for assignment.
6. For each assignment compute `mappingConfidence` from:
   - window source reliability
   - proximity to apex/window center
   - agreement between braking and cornering events
   - lap confidence

### Corner numbering

- Always stable in driving order from start/finish (`Corner 1..N`).
- If manual named corners exist, UI label = `name` with index prefix, e.g. `C4 Hairpin`.

### Fallback behavior

If corner mapping confidence is weak:

- mark corner `Low confidence` in details
- suppress action recommendations for that corner
- only allow soft caution text (`Insufficient data for reliable corner coaching`).

---

## 6) Per-Corner Metrics

All metrics computed from existing IMU-based normalized lap signals and stored peaks.

1. **Brake start position (`brakeStartPercent`)**
   - Meaning: where meaningful deceleration begins before apex.
   - Approximation: first sample before apex crossing negative longitudinal threshold sustained for N samples.
   - Limitation: no true speed; threshold depends on mounting/noise.

2. **Brake peak position (`brakePeakPercent`)**
   - Meaning: strongest decel point.
   - Approximation: min `longitudinalAccel` in corner entry window or mapped braking peak index.
   - Limitation: acceleration proxy, not brake pressure.

3. **Brake duration (`brakeDurationPercent`)**
   - Meaning: length of braking phase.
   - Approximation: continuous interval below brake threshold.
   - Limitation: coasting vs light braking ambiguity.

4. **Entry aggressiveness (`entryDecelMean`)**
   - Meaning: average deceleration in entry slice.
   - Approximation: mean negative longitudinal accel in entry subsection.
   - Limitation: influenced by kart bumps/noise.

5. **Cornering intensity / rotation proxy (`rotationPeak`)**
   - Meaning: turning demand/aggressiveness.
   - Approximation: local peak `yawRateAbs` and |`lateralAccel`|.
   - Limitation: phone orientation and vibration affect absolute values.

6. **Mid-corner stability (`midCornerStability`)**
   - Meaning: smoothness through center phase.
   - Approximation: variance of `yawRateAbs` + lateral signal in middle window.
   - Limitation: higher variance may reflect line correction or track bumps.

7. **Exit acceleration proxy (`exitAccelMean`)**
   - Meaning: how strongly kart accelerates out.
   - Approximation: mean positive longitudinal accel in exit window.
   - Limitation: not true wheel speed; traction events not directly measured.

8. **Local time loss (`localTimeMs`)**
   - Meaning: corner-level contribution to delta.
   - Approximation: proportioned segment time from sector timing / normalized duration and comparison deltas.
   - Limitation: without GPS, exact corner split boundaries are approximate.

9. **Lap-to-lap consistency metrics**
   - Meaning: repeatability at corner.
   - Approximation: std-dev of brake start, brake peak, exit accel, local time over clean laps.
   - Limitation: low lap count can overstate confidence.

10. **Corner confidence**
   - Meaning: trustworthiness of metric bundle.
   - Approximation: weighted aggregate of lap confidence, mapping confidence, signal quality, sample count.

---

## 7) Reference Model

### Reference priority order

For each corner, choose first available high-quality reference:

1. **Robust session reference (preferred):** median metrics across clean high-confidence laps (trimmed set).
2. **Best clean session lap:** only if robust median insufficient sample count.
3. **Track profile prior:** use typical zones/boundaries as positional prior and broad expectation envelope.
4. **Fallback:** current best available lap with low confidence penalty.

### Anti-overfitting safeguards

- Do not rely on single "lucky" lap when >=3 clean laps exist.
- Use trimmed median (drop top/bottom outlier lap per metric when possible).
- Require minimum sample count before emitting strong advice.
- Degrade confidence when reference source quality is low.

---

## 8) Coaching Rule Engine

New deterministic engine (e.g., `domain/CornerCoachingRuleEngine.kt`) producing explainable outputs.

### Rule template

Each rule defines:

- required signals
- preconditions
- deterministic decision logic
- output text template
- confidence contribution

### Core v1 rules

1. **R1: Brake Earlier**
   - Required: brake start/peak, exit accel, local time.
   - Preconditions: corner confidence >= 0.6, >=3 usable laps.
   - Logic: if driver brake point is consistently later than reference by threshold AND exit/local time worse.
   - Output: `Corner X: Brake earlier into this corner to stabilize entry and protect exit.`
   - Confidence: increases with repeatability across laps.

2. **R2: Brake Later**
   - Required: brake timing + stability + local time.
   - Preconditions: stable corner and no instability signal.
   - Logic: if braking consistently earlier than reference with no gain and stable yaw profile.
   - Output: `Corner X: You can brake slightly later here.`

3. **R3: Release Brakes Sooner**
   - Logic: long brake duration + weak exit accel + delayed rotation completion.
   - Output: `Corner X: Release brake a touch earlier to improve exit drive.`

4. **R4: Trail Braking Promising**
   - Required: overlap proxy (decel continues into rising yaw), local gain trend.
   - Logic: laps with moderate entry brake overlap show better local time/exit.
   - Output: `Corner X: Trail braking here looks promising—keep it smooth.`

5. **R5: Exit Speed Focus**
   - Logic: exit accel consistently below reference and dominant local time loss near corner end.
   - Output: `Corner X: Focus on exit speed; prioritize earlier clean acceleration.`

6. **R6: Very Consistent Corner**
   - Logic: low variance in brake timing, rotation, and local time over clean laps.
   - Output: `Corner X is very consistent—keep this approach.`

7. **R7: Inconsistent Corner**
   - Logic: high variance across key metrics and no clear positive pattern.
   - Output: `Corner X is inconsistent; prioritize repeatable brake point first.`

8. **R8: Insufficient Confidence (suppression/caution)**
   - Logic: low mapping/signal/reference quality.
   - Output: `Corner X: Data confidence is low; collect more clean laps.`
   - Behavior: suppress strong action rules.

### Rule arbitration

- Score candidates by `(estimatedGainMs * confidence * priorityWeight)`.
- At most 1 primary action insight per corner in Top 3 list.
- Positive/consistency insights may coexist in detailed view.

---

## 9) Confidence and Reliability

Define `cornerInsightConfidence` as weighted score:

- `lapQualityComponent` (clean lap ratio + average lap confidence)
- `mappingComponent` (corner mapping confidence)
- `signalComponent` (noise/peak clarity)
- `consistencyComponent` (agreement across laps)
- `referenceComponent` (reference source quality + sample count)

Example weights (tunable): 0.25 / 0.25 / 0.15 / 0.20 / 0.15.

### Suppression policy

- `confidence < 0.45`: no actionable instruction; only caution.
- `0.45..0.65`: soft recommendation with hedged wording.
- `> 0.65`: direct recommendation.

Also suppress when:

- fewer than 2 usable clean laps for that corner
- disturbed laps dominate available data
- conflicting signals (rule disagreement beyond threshold)

---

## 10) Output Text Design

Tone requirements:

- short
- direct
- specific (`Corner number + action`)
- non-absolute for medium confidence

Text categories:

1. **Action recommendation:**
   - `Corner 2: Brake ~2–3% earlier and keep entry straight.`
2. **Positive reinforcement:**
   - `Corner 3: Very consistent—keep this line and brake timing.`
3. **Consistency observation:**
   - `Corner 4: Brake point varies lap-to-lap; lock a repeatable marker.`
4. **Uncertainty/caution:**
   - `Corner 5: Low confidence from limited clean laps; gather more data.`

Formatting constraints:

- Headline <= ~90 chars.
- Optional evidence line <= ~120 chars.
- Avoid jargon that implies unavailable measurements (e.g., exact km/h speed).

---

## 11) UI Integration

### A) Start page nutshell summary (latest session card)

Location: `MainFragment` summary area.

Content:

- Top 3 actions (ranked)
- strongest corner
- biggest opportunity
- CTA: `View corner details`

### B) Detailed corner coaching screen

Possible placement:

- new fragment under existing navigation (e.g., `CornerCoachingFragment`), or section inside `ComparisonFragment` with dedicated tab.

Must show:

- list of all mapped corners
- per-corner insight + confidence + estimated gain
- sort toggles: `Track order` / `Biggest gain` / `Least consistent`

### C) Optional comparison/map integration

- Add corner insight markers to existing map overlay pipeline (`MapOverlayProjector` + `TrackMapOverlayView`) using corner index labels.
- Keep optional for v1.1 if UI scope too large; v1 requires text-first stable output.

---

## 12) Data Persistence

### Persist in `Session` JSON (recommended)

Add compact stable payload:

- `cornerCoachingSummary`
- `cornerCoachingInsights` (final human-facing objects)
- optional minimal per-corner diagnostics (`cornerConfidence`, `estimatedGainMs`, `ruleId`)

### Compute on-demand (do not fully persist)

- raw intermediate per-lap/per-corner metric matrices (`CornerPerformance` arrays)
- full rule candidate traces

Reason: avoid JSON bloat while preserving UI-ready deterministic outputs.

### Versioning

- Increment `Session.DEFAULT_PROCESSING_VERSION` and update reprocessing path.
- Backward compatibility:
  - older sessions without corner coaching load with empty defaults.
  - reanalysis can backfill when raw samples exist.

---

## 13) Architecture Impact

### Data layer

- `data/Session.kt`: new corner coaching fields.
- New models in `data` or `domain` package (prefer domain-first + data DTO mirroring if needed).
- `SessionStorageManager`: JSON read/write migration handling.

### Domain layer

Likely additions:

- `CornerWindowBuilder`
- `CornerPerformanceExtractor`
- `CornerReferenceResolver`
- `CornerCoachingRuleEngine`
- `CornerCoachingAnalyzer` (or extend `DrivingCoachAnalyzer` with submodule)

Likely touched existing classes:

- `DrivingCoachAnalyzer` (delegate to new corner-centric pipeline)
- `TrackLayoutMapper` (enhanced mapping helpers)
- `TimeLossCalculator` (if needed for localized gain estimation reuse)

### UI layer

- `SessionViewModel`: exposes `cornerCoachingSummary` directly from the `Session` model (no dedicated UI wrapper type).
- `MainFragment`: nutshell card.
- New adapter/model for corner list screen.
- Optionally `ComparisonFragment` integration.

### Service/sensor layer

- No direct recording changes required for v1.

---

## 14) Implementation Plan

### Phase 1 — Corner mapping foundation

- Implement `CornerWindowBuilder` with detection/layout/sector fallback chain.
- Add unit tests for stable corner indexing and fallback confidence.

### Phase 2 — Metric extraction

- Implement `CornerPerformanceExtractor` over normalized laps.
- Compute per-corner per-lap metrics + signal quality.
- Add deterministic metric tests on simulated sessions.

### Phase 3 — Reference + consistency

- Implement `CornerReferenceResolver` (priority + trimmed median).
- Implement `CornerConsistency` computation.
- Add tests for anti-overfit behavior.

### Phase 4 — Coaching rule engine

- Implement rule set R1..R8 + arbitration.
- Generate `CornerCoachingInsight` and `CornerCoachingSummary`.
- Add traceable `ruleId` for debugging.

### Phase 5 — Pipeline integration

- Integrate analyzer into session post-processing path.
- Maintain compatibility with existing `DrivingCoachAnalyzer` outputs.

### Phase 6 — Persistence + UI

- Add session fields + migration/reanalysis support.
- Implement main summary card + detailed corner coaching screen.

### Phase 7 — Tuning + release hardening

- Parameter tuning on simulated + real sessions.
- Add threshold constants and debug toggles for calibration.

---

## 15) Validation Strategy

No GPS ground truth required; validate via repeatability and expected directional behavior.

1. **Simulated sessions**
   - Use existing `SimulatedSessionGenerator` with controlled perturbations:
     - shift braking earlier/later for selected corner
     - degrade exit acceleration in one corner
     - inject lap inconsistency
   - Expect specific rule triggers.

2. **Repeatability checks**
   - Same input session must produce identical corner coaching outputs.

3. **Cross-lap consistency validation**
   - Verify consistency scores rise with reduced variance in synthetic runs.

4. **Regression test suite**
   - Golden JSON snapshots for representative sessions.
   - Ensure no unexpected coaching churn after unrelated changes.

5. **Manual expert review workflow**
   - Inspect 10–20 stored real sessions.
   - Compare generated coaching against chart/marker evidence.

6. **Failure/safety cases**
   - low-confidence laps only
   - heavily disturbed sessions
   - missing layout/detected corners
   - single-lap sessions
   - noisy signals with sparse peaks
   - Expect suppression/caution instead of strong instruction.

---

## 16) Risks and Limitations

1. **No true speed / position**: all "entry/exit speed" feedback is proxy-based.
2. **Phone mounting variability**: affects absolute thresholds and noise profile.
3. **Corner boundary approximation**: normalized-percent mapping can drift without absolute track position.
4. **Sparse clean laps**: weak references can mislead if not suppressed.
5. **Over-interpretation risk**: language must avoid false certainty.

Mitigation:

- confidence-gated outputs
- deterministic explainable rules
- conservative wording under uncertainty
- evidence lines in detail view

---

## 17) Concrete Deliverables

1. **New models**
   - `CornerWindow`, `CornerPerformance`, `CornerReference`, `CornerConsistency`, `CornerTechniqueSignal`, `CornerCoachingInsight`, `CornerCoachingSummary`.

2. **New analyzers/components**
   - `CornerWindowBuilder`
   - `CornerPerformanceExtractor`
   - `CornerReferenceResolver`
   - `CornerCoachingRuleEngine`
   - `CornerCoachingAnalyzer`

3. **Pipeline integration**
   - session analysis flow updated to compute/store corner coaching.

4. **UI additions**
   - main summary card
   - detailed corner coaching screen + adapters/ui models
   - optional map/comparison hooks

5. **Persistence updates**
   - `Session` schema extension
   - storage migration + reprocessing version bump

6. **Tests**
   - unit tests for mapping, metrics, reference resolver, rules, confidence gating
   - regression/golden tests for full session outputs

7. **Documentation updates**
   - `README.md` feature list update after implementation
   - `docs/PROJEKTDOKUMENTATION.md` architecture/data-flow additions
   - calibration/tuning notes for thresholds and confidence.

---

## Suggested Kotlin Placement (non-binding)

- `app/src/main/java/com/kartingtracker/domain/corner/`:
  - `CornerWindowBuilder.kt`
  - `CornerPerformanceExtractor.kt`
  - `CornerReferenceResolver.kt`
  - `CornerCoachingRuleEngine.kt`
  - `CornerCoachingAnalyzer.kt`
  - `CornerCoachingModels.kt`
- `app/src/main/java/com/kartingtracker/ui/cornercoaching/`:
  - `CornerCoachingFragment.kt`
  - `CornerCoachingAdapter.kt`
  - `CornerCoachingUiModels.kt`

This structure keeps v1 deterministic, explainable, and aligned with the current architecture and IMU-only constraints.
