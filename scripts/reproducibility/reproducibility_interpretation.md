# Reproducibility Assessment - Interpretation Guide

Companion to `reproducibility_assessment.groovy`. Use this to read off the
values once the script finishes. Numbers are for repeat acquisitions of the
**same field** on the **same scope** (within-instrument reproducibility).

---

## Headline number for the paper

**`area_fraction_cv`** in `collagen_across_all.csv` -- CV of collagen area
fraction across the 4 acquisitions. This is the one number a reviewer will
fixate on: "how much does our collagen measurement bounce around when we
re-image the same region?"

| CV | Interpretation |
|---|---|
| **< 0.05 (5%)** | Excellent. Comparable to well-controlled fluorescence intensity reproducibility. |
| **0.05 - 0.10** | Good. Defensible as "reproducible" in a methods paper. |
| **0.10 - 0.20** | Acceptable but flag as a known limitation; investigate dominant source (focus drift? exposure jitter? polarizer hysteresis?). |
| **> 0.20** | Problematic. Don't claim reproducibility without a fix or a caveat. |

For context: the QUAREP-LiMi intensity reproducibility working group treats
~5% inter-day CV as the bar for a well-calibrated system; biological imaging
routinely accepts 10-15%.

---

## Phase 2 - Mask agreement (`collagen_pairwise.csv`, `collagen_across_all.csv`)

### Pairwise Dice
| Dice | Interpretation |
|---|---|
| **> 0.90** | Excellent - masks essentially identical. |
| **0.80 - 0.90** | Good. Typical for repeat imaging of a stable specimen with a deterministic thresholder. |
| **0.70 - 0.80** | Borderline. Likely focus or exposure variation pushing pixels across the threshold. |
| **< 0.70** | Indicates either misregistration, thresholder sitting on a noisy edge of the histogram, or genuine acquisition variability. Diagnose before reporting. |

Jaccard tracks Dice (`Jaccard ~= Dice / (2 - Dice)`); cite Dice unless your
reviewers prefer IoU.

### Across-all intersection / union ratio
The fraction of "anyone called it collagen" pixels that **all 4** called
collagen. With 4 acquisitions this is harsher than pairwise Dice -- > 0.70 is
solid, > 0.80 is strong.

### Per-image area fractions (`collagen_per_image.csv`)
Look at the spread directly. If three are tight and one is an outlier, that
one acquisition probably has a focus or exposure issue worth flagging in the
log.

---

## Phase 1 - Raw biref agreement (`pairwise_metrics.csv`, `cv_summary.csv`)

### Pairwise Pearson r
| r | Interpretation |
|---|---|
| **> 0.95** | Strong agreement of underlying signal. |
| **0.90 - 0.95** | Good. |
| **< 0.90** | Misregistration is the usual culprit -- visually overlay two of them before blaming the scope. |

### RMSE / MAE
Interpret in pixel-value units of the biref image (8-bit -> 0..255;
16-bit -> 0..65535). Useful as an absolute companion to r, especially for
spotting systematic offset (large bias) vs. noise (large sd_diff with small
bias).

### Bias and Bland-Altman 95% LoA
- Bias near 0 -> no systematic drift between acquisitions.
- Narrow LoA (e.g., bias +/- 5% of dynamic range) -> tight agreement across
  the histogram.
- Bias significantly nonzero -> exposure or polarizer-zero drift between
  sessions; worth investigating.

### Per-pixel CV (whole image)
**Don't quote the whole-image CV** -- background dominates and inflates
apparent variability. Use the **restricted CV** (over the union of collagen
masks; in `collagen_across_all.csv` as `restricted_cv_*`) instead. Same
thresholds as the headline: < 5% excellent, < 10% good, > 20% concerning.

---

## What a good result looks like - draft paragraph for the paper

> "Reproducibility was assessed across 4 repeat 20x PPM acquisitions of the
> same pancreatic-tissue region. Pairwise Pearson correlation of the
> birefringence images was r = 0.9X (mean across 6 pairs); pairwise Dice
> coefficient of the collagen mask produced by the same thresholder was 0.8X.
> The coefficient of variation of the collagen area fraction across the 4
> acquisitions was X.X%, comparable to recommended QUAREP-LiMi intensity
> reproducibility benchmarks."

Fill in `0.9X`, `0.8X`, `X.X%` from the script output.

---

## Diagnostic table - what each pattern means

| Symptom in metrics | Likely cause |
|---|---|
| Low Pearson r (< 0.85) but high mask Dice | Misregistration -- pixel positions shifted but masks still cover similar areas |
| High r, low Dice | Thresholder sitting on a flat shoulder of the histogram; small intensity changes flip class |
| One outlier image dragging `area_fraction_cv` up | Focus failure or saturation in that acquisition (cross-check the server log) |
| Large bias between pairs | Exposure or polarizer-zero drift between sessions |
| High whole-image CV but low restricted CV | Background noise dominating; not a real problem -- quote restricted CV |

If the numbers come back ugly, `cv_map.png` and `collagen_agreement_map.png`
localize the disagreement so you can diagnose (edges? whole tissue? a
specific region with focus issues?).

---

## Practical notes for the run

- **Full-resolution, tile-streamed, multithreaded.** The script reads
  ~1024 px tiles at native resolution and accumulates online sums; no
  whole-image arrays are held. Worker threads compute per-tile partial sums
  and the main thread merges them.
- **THREADS knob.** Defaults to `min(8, cores - 1)`. The bottleneck for
  Phase 2 is the classifier's per-tile inference -- speedup scales with how
  many tiles the classifier can process concurrently. Drop to 1-2 if the JVM
  starts thrashing or the disk gets saturated.
- **IN_FLIGHT_PER_THREAD knob.** Defaults to 4. Bounds memory by limiting
  how many submitted-but-not-yet-merged tiles can pile up. Lower it if memory
  pressure shows.
- **Skip phases for fast iteration.** Set `RUN_PHASE_1 = false` or
  `RUN_PHASE_2 = false`. Phase 2 (classifier) is the slow one; Phase 1
  alone gives you Pearson r, RMSE, MAE without paying the classifier cost.
- **First sanity check**: the script errors out if the four images do not
  share native dimensions. If they don't, register them first (sub-pixel
  alignment) before re-running.
- **Misregistration check before trusting the numbers**: if Phase 1 returns
  Pearson r below ~0.90, open two of the images side by side in QuPath and
  visually verify that the same anatomy lands at the same pixel coordinates.
  Low r from misregistration looks identical to low r from a noisy scope --
  the metrics cannot tell them apart.
- **Progress logging.** The script prints `tiles done / total` with running
  rate and ETA every `PROGRESS_EVERY_TILES` tiles in each phase, so you can
  estimate completion time without watching it.
