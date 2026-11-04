# PPM Reference Slide (Sunburst Calibration)

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Creates a hue-to-angle calibration from a PPM reference slide with a sunburst (radial spoke) pattern. This calibration maps HSV hue values from PPM images to physical orientation angles (0-180 deg) through a table measured from the spokes. The resulting calibration file (.npz) is required for all PPM analysis workflows.

## When to Run

- **First-time setup** after completing [Polarizer Calibration](polarizer-calibration.md) and [Birefringence Optimization](ppm-birefringence-optimization.md)
- When changing **objectives or optical components** that affect color rendering
- Periodically to verify calibration stability

## Prerequisites

1. **Completed Polarizer Calibration** -- rotation stage must be calibrated
2. **PPM Reference Slide** -- A slide with a sunburst/fan pattern of known orientations (radial spokes)
3. **Microscope connection** -- QPSC must be connected
4. **Proper focus** -- Center the sunburst pattern in the field of view and focus
5. **Camera settings** -- Use low-angle PPM settings (crossed or near-crossed) for best color contrast

> **WARNING: Avoid pixel saturation (overexposure).** Saturated pixels have corrupted hue values -- any channel clipping at 255 will shift the measured hue and produce an incorrect calibration. Before running this workflow, open Camera Control and verify that no channels are clipping. If the image looks washed out or white, **reduce exposure** before proceeding. Check the histogram or per-channel RGB readout in the Live Viewer to confirm no saturation.

## How It Works

1. **Image acquisition** -- Acquires a PPM image of the reference slide at the current camera settings
2. **Hub** -- Every spoke edge points at the hub of the sunburst, so the hub is found where the edge directions meet. A manually clicked center is used only as a hint for where to look.
3. **Spoke axes** -- Each spoke is a flat-topped band, so its position is taken halfway between its two edges, at many radii and on both halves of the pattern. A straight line through those points is the spoke's axis and gives its angle; the hub is then refined to where all the axes cross.
4. **Spoke hues** -- The hue of each spoke is read from the middle of the spoke (the central 40% of the spoke spacing), leaving out the blurred edges and any pixel with a clipped channel.
5. **Hue-to-angle table** -- A smooth monotonic curve through the spokes maps hue to angle. The hue does not change at a constant rate with angle, so a straight line alone is several degrees off between some spokes (2 to 3.5 deg RMS on the PPM rig's reference slide in October 2026). The straight line is still saved for display colormaps.
6. **Validation** -- Reports the error estimate of the table and of the straight line, and generates a verification plot
7. **Calibration saved** -- Writes a .npz file used by all PPM analysis tools

## Dialog Settings

### Calibration Folder

| Setting | Default | Description |
|---------|---------|-------------|
| **Calibration Folder** | Last used / config dir | Where all calibration output files are saved |

### Camera Setup

Before starting calibration, use the **Open Camera Control...** button to:
- Set the polarizer to a low angle (crossed or near-crossed)
- Verify exposure settings produce good color contrast on the reference slide
- **Check for pixel saturation** -- overexposed (clipped) pixels produce incorrect hue values and will corrupt the calibration. Use the Live Viewer histogram or RGB readout to confirm no channel is saturating

### Detection Settings

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Number of Spokes** | 16 | 4-32 | Number of unique orientation spokes in the sunburst pattern. Common values: 16 (11.25 deg spacing), 12 (15 deg spacing), 8 (22.5 deg spacing) |
| **Saturation Threshold** | 0.1 | 0.01-0.5 | Minimum HSV saturation to classify a pixel as colored (foreground). Lower values include more pixels but may pick up noise. Higher values are stricter but may miss pale spokes |
| **Value Threshold** | 0.1 | 0.01-0.5 | Minimum HSV brightness to classify a pixel as foreground. Helps exclude dark regions like slide edges and shadows |

### Advanced Radial Detection Settings

These are in a collapsible panel for fine-tuning when default detection fails:

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Inner Radius (px)** | 30 | 10-200 | Smallest distance from the hub at which spokes are measured. Raised to 30% of the pattern radius if smaller, because the spokes blur together near the hub |
| **Outer Radius (px)** | 150 | 50-500 | Largest distance from the hub at which spokes are measured. Lowered to 90% of the pattern radius if larger, so the measurement stays inside the spokes. If the two settings do not fit the pattern at all, 30-90% of the pattern radius is used and a RADII warning says so |

### Calibration Name

| Setting | Default | Description |
|---------|---------|-------------|
| **Calibration Name** | Auto-generated | Optional custom name (letters, numbers, underscore, hyphen only). If empty, auto-generates as `sunburst_cal_YYYYMMDD_HHMMSS` |

## Output Files

All files are saved to the calibration folder:

| File | Description |
|------|-------------|
| `{name}_image.tif` | The acquired calibration image |
| `{name}.npz` | Calibration data file (used by PPM analysis workflows) |
| `{name}_plot.png` | Verification plot: the measured spoke axes drawn on the image, and the hue-to-angle table with the straight line for comparison |
| `{name}_mask.png` | Debug segmentation mask (if detection issues occur) |

## Results Dialog

After calibration, a results dialog shows:

- **R-squared value** -- Quality of the straight-line fit. Values > 0.95 are [GOOD]; < 0.85 may indicate issues. Analysis uses the table, whose error estimate is on the calibration plot
- **Spokes detected** -- How many of the expected spokes were found
- **Calibration plot** -- Visual showing hue values mapped to angles
- **Warnings** -- Any issues detected during calibration

### If Calibration Fails

The results dialog provides several recovery options:

1. **Tune Thresholds** -- Opens an interactive preview to adjust saturation/value thresholds and see the effect on the segmentation mask in real time
2. **Manual Center Selection** -- Click on the acquired image near the sunburst center. The click tells the calibration where to look; the hub itself is still measured from the spokes, within 60 px of the click
3. **Go Back and Redo** -- Returns to the parameter dialog to try different settings

## Troubleshooting

| Problem | Likely Cause | Solution |
|---------|-------------|----------|
| All BLACK mask | Thresholds too high | Lower saturation and/or value threshold |
| All WHITE mask | Thresholds too low | Raise saturation and/or value threshold |
| Low R-squared | Poor spoke detection | Adjust thresholds, check focus, try manual center |
| Few spokes found | Center misdetected | Use manual center selection |
| SPOKE_SPACING or SPOKE_FIT warning | A spoke was measured off its true position (dust, a damaged spoke, or the wrong hub) | Check the spoke axes on the plot; clean the slide or click the center manually |
| NOT_MONOTONIC warning | Spoke hues do not run in one direction around the pattern, so no table was made and the straight line is used | Usually saturation or wrong spoke detection; reduce exposure and check the plot |
| Noisy fit | Background not uniform | Clean slide, improve illumination |
| Incorrect calibration / wrong angles | Pixel saturation (overexposure) | Reduce exposure until no channel clips at 255. Check histogram in Live Viewer |
| Spokes all appear same hue | Camera saturated | Significantly reduce exposure; saturated pixels lose hue information |

## Related Workflows

- [Polarizer Calibration](polarizer-calibration.md) -- Must be completed first
- [Birefringence Optimization](ppm-birefringence-optimization.md) -- Find optimal angle first
- [PPM Hue Range Filter](ppm-hue-range-filter.md) -- Uses this calibration for angle mapping
- [PPM Polarity Plot](ppm-polarity-plot.md) -- Uses this calibration for angle mapping
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Uses this calibration for batch processing
