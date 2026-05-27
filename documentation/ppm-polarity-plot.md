# PPM Polarity Plot

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Computes per-object polarity (orientation distribution) for a user-chosen set of annotations or detections, writes the circular statistics back onto each object as measurements, and displays a combined polar histogram (rose diagram) in the dialog. Useful for verifying alignment per object (e.g. PPM-segmented fiber detections) and for comparing aligned populations.

## Prerequisites

1. **Completed [Sunburst Calibration](ppm-reference-slide.md)** -- The hue-to-angle calibration (.npz) must exist. This maps HSV hue values to fiber orientation angles.
2. **A PPM color (angle) image open** -- Open a post-processed PPM image (not the birefringence grayscale). The image must not have saturated pixels, as overexposure corrupts hue values and produces incorrect angle measurements.
3. **Objects to analyze** -- One or more annotations or detections in the hierarchy. They may be unclassified; the dialog lists all classes present so you can include or exclude any subset.

## How It Works

1. Opens a small **object-selection dialog** offering three choices:
   - Use current selection (only enabled when objects are selected)
   - All annotations, filtered by class (multi-select)
   - All detections, filtered by class (multi-select)
2. Iterates over the resolved object list, and for each object:
   - Reads the PPM image data within the object's ROI
   - Converts each pixel's HSV hue to an orientation angle using the active calibration
   - Filters out pixels below the birefringence threshold (non-birefringent background)
   - Bins the remaining angles into a histogram (default: 18 bins of 10 deg each)
   - Writes per-object measurements back to the object's measurement list (see below)
3. Sums histograms across all processed objects and combines per-object circular statistics into population-level mean / R / std for axial data
4. Displays the aggregate result as a semi-circular rose diagram (0-180 deg) with the object count and class breakdown in the title

### Per-object measurements written

Each processed object receives the following measurements (visible in QuPath's measurement table, exportable as TSV):

| Measurement | Meaning |
|-------------|---------|
| `PPM Polarity: Mean angle (deg)` | Circular mean orientation for this object |
| `PPM Polarity: Circ std (deg)` | Circular standard deviation |
| `PPM Polarity: Resultant length` | Alignment strength R (0 = random, 1 = perfectly aligned) |
| `PPM Polarity: Valid pixels` | Pixel count that passed the biref threshold |
| `PPM Polarity: Dominant bin center (deg)` | Centre of the highest-count histogram bin |

## Rose Diagram

The plot is a **semi-circular polar histogram** where:

- **Angle** (0-180 deg) represents fiber orientation
  - 0 deg = horizontal
  - 90 deg = vertical
  - 180 deg = horizontal (equivalent to 0 deg for undirected data)
- **Radius** of each wedge represents the number of pixels at that orientation
- Wedges are arranged around the half-circle, giving an intuitive view of the dominant fiber directions

## Circular Statistics

The panel displays four key metrics:

| Metric | Description |
|--------|-------------|
| **Circular Mean** | The average fiber orientation direction (in degrees). This is the "preferred direction" of the fibers |
| **Circular Std Dev** | Spread of orientations around the mean. Low values indicate highly aligned fibers; high values indicate random/isotropic organization |
| **Resultant Length** | A measure of alignment strength, ranging from 0 (perfectly random) to 1 (perfectly aligned). Values > 0.5 indicate strong directional preference |
| **Valid Pixels** | Number of pixels that passed the birefringence threshold and contributed to the statistics |

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| **Histogram Bins** | 18 | Number of angular bins (18 bins = 10 deg per bin) |
| **Birefringence Threshold** | 100.0 | Minimum birefringence intensity for a pixel to be included. Filters out non-birefringent background |

## Interpreting Results

- **Single dominant peak** -- Fibers are strongly aligned in one direction
- **Two peaks 90 deg apart** -- Orthogonal fiber populations (e.g., woven tissue)
- **Uniform distribution** -- Random/isotropic fiber organization
- **Resultant length > 0.5** -- Strong alignment; < 0.2 is near-random

The aggregate plot in the dialog is a histogram sum across all processed objects; the per-object measurements written to the measurement list let you filter or compare orientation behavior on a per-detection basis (useful once PPM segmentation has split tissue into many detection objects).

## Related Workflows

- [PPM Hue Range Filter](ppm-hue-range-filter.md) -- Spatial visualization of the same angle data
- [Surface Perpendicularity](surface-perpendicularity.md) -- Orientation relative to boundaries
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Run polarity analysis across all annotations
