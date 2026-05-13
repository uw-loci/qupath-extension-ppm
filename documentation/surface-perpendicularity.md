# Surface Perpendicularity (PS-TACS)

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Analyzes fiber orientation relative to an annotation boundary (e.g., tumor-stroma interface) to quantify perpendicularity scores. This implements the **PS-TACS** (Perpendicularity Score for Tumor-Associated Collagen Signatures) method described in:

> Qian et al., *American Journal of Pathology*, 2025; DOI: 10.1016/j.ajpath.2025.04.017

Perpendicularity analysis reveals how collagen fibers are organized relative to tissue boundaries -- a feature with diagnostic and prognostic significance in cancer biology.

## Prerequisites

1. **Completed [Sunburst Calibration](ppm-reference-slide.md)** -- The hue-to-angle calibration (.npz) must exist. This is created from a PPM reference slide and is required for all PPM analysis.
2. **Low-angle PPM image open** -- Open a PPM **birefringence** or **sum** image in QuPath. These are the post-processed images from a PPM acquisition (not the raw angle images). Ensure the image was acquired with low-angle (crossed or near-crossed) polarizer settings for good color contrast.
3. **Boundary annotations drawn** -- Draw annotations in QuPath that define the tissue boundary of interest (e.g., tumor-stroma interface). Annotations must have a **PathClass assigned** (e.g., "Tumor", "Boundary") so the tool can identify them.
4. **Camera settings were appropriate** -- The source PPM image must not have saturated pixels, as overexposure corrupts hue values and produces incorrect fiber angle measurements. If in doubt, re-acquire with lower exposure.

## How It Works

1. Extracts the boundary contour from the boundary annotation
2. Defines an analysis zone by dilating from the boundary (inside, outside, or both)
3. For each valid pixel in the zone, computes the **deviation angle** between the fiber orientation and the local boundary normal
4. Classifies each pixel as parallel (0-30 deg), oblique (30-60 deg), or perpendicular (60-90 deg)
5. Computes the PS-TACS score and summary statistics

## Analysis Parameters

These parameters are set in the [Batch PPM Analysis](batch-ppm-analysis.md) panel when running perpendicularity as part of a batch, or use defaults for single-annotation analysis. All parameter values are remembered between sessions.

| Parameter | Default | Description |
|-----------|---------|-------------|
| **Boundary Class** | (last selected) | The annotation class that defines the tissue boundary (e.g., "Tumor", "Stroma"). The UI remembers your last selection. |
| **Dilation (um)** | varies | Distance from the boundary to include in the analysis zone, in micrometers |
| **Zone Mode** | "Inside" | Which side of the boundary to analyze: "Inside" (toward tumor), "Outside" (away from tumor) |
| **PS-TACS Threshold (deg)** | varies | Angle threshold for perpendicular classification. Fibers with deviation > threshold are classified as perpendicular |
| **Saturation Threshold** | varies | Minimum saturation (0-1) for a pixel to be included in the analysis. Excludes very desaturated or grayscale areas. |
| **Value Threshold** | varies | Minimum brightness (0-1) for a pixel to be included. Excludes very dark pixels. |
| **Min Pixel Intensity** | 100 | Minimum max(R,G,B) to include a pixel. Excludes dark absorbing tissue like hematoxylin staining (0-255). |
| **Fill Holes** | true | Whether to fill holes in the boundary annotation before computing the boundary normals |
| **Use Pixel Classifier** | false | Optional: apply a pre-trained pixel classifier to refine the foreground mask (instead of using hue-based thresholding) |

### Live Mask Preview

Click **"Preview mask..."** to open a live preview window showing which pixels would be excluded by the current threshold settings. The preview displays:

- **HSV/intensity exclusions**: Red overlay marks pixels excluded by saturation, value, or minimum RGB intensity thresholds (applied to the current RGB image)
- **Biref threshold**: Red overlay marks pixels below the biref-image threshold (only shown if a biref sibling exists)

The preview window updates in real-time as you adjust spinner values, and includes controls to change the preview size and overlay opacity. This allows you to refine thresholds before running the full analysis.

### Pixel Classifier Mode

If **Use Pixel Classifier** is enabled, the workflow will apply a trained QuPath pixel classifier to identify fibers instead of using hue-based thresholding. When using this mode:

- **A biref sibling image must be present in the project** for the current entry. The classifier runs against the birefringence image (+7 / -7 PPM), not the currently open sum image.
- The pixel classifier must be trained on biref images and must have the correct number of input channels (typically 3 for RGB). If you see a channel compatibility error, verify that your classifier expects the same number of channels as the biref image.
- If a required biref sibling is missing, the workflow will show an error message. In this case, ensure you have acquired the full PPM image set (multiple angles) for that sample region.

## Results Panel

### Output Detections

The workflow creates detection objects visible in the QuPath viewer:

- **PPM-Foreground**: A binary mask detection showing which pixels were included in the analysis (white = included, black = excluded by HSV/intensity/biref thresholds)
- **PPM-Zone**: The interrogation zone ring showing the exact area analyzed. This spans from the annotation boundary outward (or inward, or both) by the dilation distance, depending on the selected zone mode. Shows the "Dilation (um)" and "Zone area (px)" measurements.

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
