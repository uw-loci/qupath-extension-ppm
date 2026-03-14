# Surface Perpendicularity (PS-TACS)

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Analyzes fiber orientation relative to an annotation boundary (e.g., tumor-stroma interface) to quantify perpendicularity scores. This implements the **PS-TACS** (Perpendicularity Score for Tumor-Associated Collagen Signatures) method described in:

> Qian et al., *American Journal of Pathology*, 2025; DOI: 10.1016/j.ajpath.2025.04.017

Perpendicularity analysis reveals how collagen fibers are organized relative to tissue boundaries -- a feature with diagnostic and prognostic significance in cancer biology.

## Prerequisites

1. **Active PPM calibration** -- A sunburst calibration must be set (see [PPM Reference Slide](ppm-reference-slide.md))
2. **PPM image open** -- A PPM birefringence or sum image
3. **Boundary annotation** -- An annotation defining the tissue boundary (e.g., tumor border drawn in QuPath)
4. **Region annotation** -- An annotation defining the analysis region (typically surrounding the boundary)

## How It Works

1. Extracts the boundary contour from the boundary annotation
2. Defines an analysis zone by dilating from the boundary (inside, outside, or both)
3. For each valid pixel in the zone, computes the **deviation angle** between the fiber orientation and the local boundary normal
4. Classifies each pixel as parallel (0-30 deg), oblique (30-60 deg), or perpendicular (60-90 deg)
5. Computes the PS-TACS score and summary statistics

## Analysis Parameters

These parameters are set in the [Batch PPM Analysis](batch-ppm-analysis.md) panel when running perpendicularity as part of a batch, or use defaults for single-annotation analysis.

| Parameter | Default | Description |
|-----------|---------|-------------|
| **Boundary Class** | (user selects) | The annotation class that defines the tissue boundary (e.g., "Tumor", "Stroma") |
| **Dilation (um)** | varies | Distance from the boundary to include in the analysis zone, in micrometers |
| **Zone Mode** | "Inside" | Which side of the boundary to analyze: "Inside" (toward tumor), "Outside" (away from tumor) |
| **PS-TACS Threshold (deg)** | varies | Angle threshold for perpendicular classification. Fibers with deviation > threshold are classified as perpendicular |
| **Fill Holes** | true | Whether to fill holes in the boundary annotation before computing the boundary normals |

## Results Panel

### Deviation Histogram

A histogram showing the distribution of deviation angles (0-90 deg) in 10 deg bins:

- **0-30 deg** = fibers running **parallel** to the boundary
- **30-60 deg** = fibers at an **oblique** angle
- **60-90 deg** = fibers running **perpendicular** to the boundary

### Three-Way Classification Bar

A horizontal stacked bar showing the percentage of pixels in each category:

| Category | Angle Range | Significance |
|----------|-------------|-------------|
| **Parallel** | 0-30 deg | Fibers aligned with the boundary (TACS-2 like) |
| **Oblique** | 30-60 deg | Intermediate alignment |
| **Perpendicular** | 60-90 deg | Fibers perpendicular to boundary (TACS-3 like) |

### Summary Statistics

| Metric | Description |
|--------|-------------|
| **Mean Deviation** | Average angle between fiber orientation and boundary normal |
| **PS-TACS Score** | Fraction of perpendicular fibers (higher = more perpendicular organization) |
| **Contour Length** | Total length of the boundary contour analyzed |
| **Valid Pixels** | Number of pixels in the analysis zone that contributed |

## GeoJSON Export

The workflow can export the analysis region and boundary as GeoJSON for use by the [ppm_library](https://github.com/uw-loci/ppm_library) Python tools. This enables further computational analysis outside of QuPath.

## Related Workflows

- [PPM Polarity Plot](ppm-polarity-plot.md) -- General fiber orientation statistics
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Run perpendicularity across all annotations
- [Back-Propagate Annotations](back-propagate-annotations.md) -- Transfer results to parent images
