# PPM Polarity Plot

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Computes and displays a polarity rose diagram (polar histogram) for the selected annotation, showing the distribution of fiber orientation angles within that region. Includes circular statistics (mean direction, circular standard deviation, resultant length) that quantify the degree and direction of fiber alignment.

## Prerequisites

1. **Active PPM calibration** -- A sunburst calibration must be set (see [PPM Reference Slide](ppm-reference-slide.md))
2. **PPM image open** -- A PPM birefringence or sum image must be the current image
3. **Annotation selected** -- Select an annotation in QuPath to define the region of interest

## How It Works

1. Reads the PPM image data within the selected annotation boundary
2. Converts each pixel's HSV hue to an orientation angle using the active calibration
3. Filters out pixels below the birefringence threshold (non-birefringent background)
4. Bins the remaining angles into a histogram (default: 18 bins of 10 deg each)
5. Displays the result as a semi-circular rose diagram (0-180 deg)

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

## Related Workflows

- [PPM Hue Range Filter](ppm-hue-range-filter.md) -- Spatial visualization of the same angle data
- [Surface Perpendicularity](surface-perpendicularity.md) -- Orientation relative to boundaries
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Run polarity analysis across all annotations
