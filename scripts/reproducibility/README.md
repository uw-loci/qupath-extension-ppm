# PPM birefringence reproducibility assessment

QuPath Groovy script for assessing reproducibility of repeat PPM birefringence
acquisitions of the same field of view. Quantifies both the raw biref signal
and the downstream collagen-mask measurement produced by a user-supplied
pixel-classifier thresholder.

## What it does

- Locates N repeat acquisitions in the open QuPath project by name pattern.
- Streams full-resolution tile reads through a bounded thread pool; no
  whole-image arrays are held.
- **Phase 1** -- per-image distribution stats (mean, sd, min, max, percentiles
  via histogram) and pairwise pixel agreement (Pearson r, RMSE, MAE,
  Bland-Altman bias and 95% limits of agreement) at the source images'
  native resolution.
- **Phase 2** -- per-image collagen pixel count and area fraction, pairwise
  mask agreement (Dice, Jaccard, TP/FN/FP), and across-all-acquisitions
  intersection / union of the collagen masks at the classifier's native
  downsample.
- **Phase 3** -- writes two visualization PNGs (per-pixel CV map of the biref
  signal; per-pixel agreement-count map of the collagen mask) from
  accumulators built during Phases 1/2 -- no extra reads or classifier work.

## Headline reproducibility number

`area_fraction_cv` in `reproducibility_output/collagen_across_all.csv` --
the coefficient of variation of the collagen area fraction across the N
repeat acquisitions. This is the metric to report in a methods paper.

## Configuration

All knobs at the top of the script:
- `IMAGE_NAME_PATTERN` -- regex matching the repeat acquisitions in your
  project
- `THRESHOLDER_NAME` -- the QuPath pixel classifier to use for the collagen
  mask (default `"collagen"`)
- `POSITIVE_CLASS_NAME` -- which class index counts as positive
- `TILE_SIZE`, `THREADS`, `IN_FLIGHT_PER_THREAD` -- performance knobs
- `RUN_PHASE_1`, `RUN_PHASE_2`, `RUN_PHASE_3` -- skip phases independently
- `VIZ_LONG_EDGE_PX` -- target long-edge size for the PNG visualization
  outputs

## Outputs (in `<project>/reproducibility_output/`)

| File | Contents |
|---|---|
| `per_image_stats.csv` | biref distribution per image |
| `pairwise_metrics.csv` | biref pairwise Pearson / RMSE / MAE / Bland-Altman |
| `collagen_per_image.csv` | per-image collagen pixel count and area fraction |
| `collagen_pairwise.csv` | pairwise Dice / Jaccard / TP / FN / FP |
| `collagen_across_all.csv` | intersection / union of all masks; **headline `area_fraction_cv`** |
| `cv_map.png` | per-pixel CV of biref (low-res visualization) |
| `collagen_agreement_map.png` | per-pixel mean agreement count of collagen mask (low-res visualization) |

## Interpretation

See `reproducibility_interpretation.md` in this folder for thresholds,
diagnostic patterns, and a draft methods-statement template.

## Citation

If you use this script in published work, cite the QPSC Brief Communication
that introduced the analysis.
