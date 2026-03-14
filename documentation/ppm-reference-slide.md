# PPM Reference Slide (Sunburst Calibration)

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Creates a hue-to-angle calibration from a PPM reference slide with a sunburst (radial spoke) pattern. This calibration maps HSV hue values from PPM images to physical orientation angles (0-180 deg) via linear regression. The resulting calibration file (.npz) is required for all PPM analysis workflows.

## When to Run

- **First-time setup** after completing [Polarizer Calibration](polarizer-calibration.md) and [Birefringence Optimization](ppm-birefringence-optimization.md)
- When changing **objectives or optical components** that affect color rendering
- Periodically to verify calibration stability

## Prerequisites

1. **Completed Polarizer Calibration** -- rotation stage must be calibrated
2. **PPM Reference Slide** -- A slide with a sunburst/fan pattern of known orientations (radial spokes)
3. **Microscope connection** -- QPSC must be connected
4. **Proper focus** -- Center the sunburst pattern in the field of view and focus
5. **Camera settings** -- Use low-angle PPM settings (crossed or near-crossed) for best color saturation

## How It Works

1. **Image acquisition** -- Acquires a PPM image of the reference slide at the current camera settings
2. **Center detection** -- Finds the center of the sunburst pattern (automatic or manual override)
3. **Radial sampling** -- Samples HSV hue values along radial lines from center to each spoke
4. **Linear regression** -- Fits a linear model: `angle = slope * hue + intercept`
5. **Validation** -- Reports R-squared value and generates a verification plot
6. **Calibration saved** -- Writes a .npz file used by all PPM analysis tools

## Dialog Settings

### Calibration Folder

| Setting | Default | Description |
|---------|---------|-------------|
| **Calibration Folder** | Last used / config dir | Where all calibration output files are saved |

### Camera Setup

Before starting calibration, use the **Open Camera Control...** button to:
- Set the polarizer to a low angle (crossed or near-crossed)
- Verify exposure settings produce good color saturation on the reference slide

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
| **Inner Radius (px)** | 30 | 10-200 | Distance from center where radial sampling begins. Increase to skip noisy/dark center pixels |
| **Outer Radius (px)** | 150 | 50-500 | Distance from center where radial sampling ends. Should reach into the colored spokes but not past them |

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
| `{name}_plot.png` | Visual verification of the linear regression fit |
| `{name}_mask.png` | Debug segmentation mask (if detection issues occur) |

## Results Dialog

After calibration, a results dialog shows:

- **R-squared value** -- Quality of the linear fit. Values > 0.95 are [GOOD]; < 0.85 may indicate issues
- **Spokes detected** -- How many of the expected spokes were found
- **Calibration plot** -- Visual showing hue values mapped to angles
- **Warnings** -- Any issues detected during calibration

### If Calibration Fails

The results dialog provides several recovery options:

1. **Tune Thresholds** -- Opens an interactive preview to adjust saturation/value thresholds and see the effect on the segmentation mask in real time
2. **Manual Center Selection** -- Click on the acquired image to manually specify the sunburst center point
3. **Go Back and Redo** -- Returns to the parameter dialog to try different settings

## Troubleshooting

| Problem | Likely Cause | Solution |
|---------|-------------|----------|
| All BLACK mask | Thresholds too high | Lower saturation and/or value threshold |
| All WHITE mask | Thresholds too low | Raise saturation and/or value threshold |
| Low R-squared | Poor spoke detection | Adjust thresholds, check focus, try manual center |
| Few spokes found | Center misdetected | Use manual center selection |
| Noisy fit | Background not uniform | Clean slide, improve illumination |

## Related Workflows

- [Polarizer Calibration](polarizer-calibration.md) -- Must be completed first
- [Birefringence Optimization](ppm-birefringence-optimization.md) -- Find optimal angle first
- [PPM Hue Range Filter](ppm-hue-range-filter.md) -- Uses this calibration for angle mapping
- [PPM Polarity Plot](ppm-polarity-plot.md) -- Uses this calibration for angle mapping
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Uses this calibration for batch processing
