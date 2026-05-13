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
| **Gaussian blur for HSV (px)** | 0 | Pre-blur sigma (in pixels) applied to the RGB image before HSV/intensity thresholding. Range: 0-10. Use > 0 to reduce noise sensitivity. |
| **Gaussian blur for biref (px)** | 0 | Pre-blur sigma (in pixels) applied to the birefringence image before thresholding. Range: 0-10. Only used in biref-threshold mode. |
| **Fill Holes** | true | Whether to fill holes in the boundary annotation before computing the boundary normals |
| **Use Pixel Classifier** | false | Optional: apply a pre-trained pixel classifier to refine the foreground mask (instead of using hue-based thresholding). Runs against the biref sibling, not the current image. |
| **Min polyline length (px)** | 20 | Minimum contiguous run of contour points (same TACS class) required to draw a polyline. Shorter runs are absorbed into adjacent same-class neighbours. |

### Extended TACS (TACS-1 / 2 / 3)

| Parameter | Default | Description |
|-----------|---------|-------------|
| **Enable TACS-1 classification** | off | Adds TACS-1 (sparse collagen) and Unclassified categories. TACS-2 / TACS-3 are produced regardless; enabling this just reclassifies low-density contour pixels. |
| **Min collagen density** | 0.10 | Normalised density threshold (0-1). Contour pixels with less than this fraction of the peak density along the contour are demoted to TACS-1. |
| **Min signal threshold** | 0.02 | Normalised density threshold (0-1). Contour pixels below this fraction of peak are dropped from classification entirely (Unclassified -- no measurable collagen near the contour). |

### Smoothing & Cleanup

| Parameter | Default | Description |
|-----------|---------|-------------|
| **Boundary smoothing (px)** | 5.0 | Gaussian sigma for smoothing the boundary mask before extracting the contour and computing normals. Removes pixel-staircase artefacts. 0 disables. |
| **TACS contour smoothing** | 10 | Moving-average window (samples) applied to TACS scores along the contour. Larger values flatten noisy TACS-2/3 alternations. 1 disables. |
| **Min collagen area (px)** | 100 | Connected components smaller than this are removed from the foreground mask. 0 disables. |
| **Mask smoothing sigma (px)** | 2.0 | Gaussian sigma applied to the analysis mask before component-area filtering. 0 disables. |

### Live Mask Preview

Click **"Preview mask..."** to open a live preview window showing which pixels would be excluded by the current threshold settings. The preview displays an overlay (default cyan) marking excluded pixels in two modes:

- **HSV/intensity exclusions**: Shows pixels excluded by saturation, value, or minimum RGB intensity thresholds (applied to the current RGB image, optionally pre-blurred)
- **Biref threshold**: Shows pixels below the biref-image threshold (only shown if a biref sibling exists and pixel classifier is not enabled; hidden when using the pixel classifier)

The preview window includes:
- **Size selector** -- Choose the region size (128, 256, or 512 px) for faster feedback on small regions
- **Opacity slider** -- Adjust overlay transparency to see the underlying image
- **Color picker** -- Select the overlay color (default is cyan to remain visible on H&E and collagen hues)
- **Help button (?)** -- Click to view an explanation of HSV/Intensity and Birefringence threshold modes

The preview updates in real-time as you adjust any threshold, blur, or opacity control, allowing you to refine parameters before running the full analysis.

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

A horizontal stacked bar showing the percentage of pixels in each category. This is the pixel-level histogram (each valid pixel inside the analysis zone is binned by its deviation angle). For the polyline-level TACS overlays drawn on the boundary itself, see **TACS Classification** below.

| Category | Angle Range | Significance |
|----------|-------------|-------------|
| **Parallel** | 0-30 deg | Fibers aligned with the boundary (TACS-2 like) |
| **Oblique** | 30-60 deg | Intermediate alignment |
| **Perpendicular** | 60-90 deg | Fibers perpendicular to boundary (TACS-3 like) |

### TACS Classification (polylines along the boundary)

The workflow produces per-contour-pixel TACS scores and draws coloured polylines on the boundary itself. Scoring follows the PS-TACS method (Qian et al., 2025): at each contour pixel, nearby valid fiber pixels within ~3 sigma of the Gaussian falloff (auto-sized from mean fiber distance from the boundary) are weighted by a Gaussian on distance, and the weighted mean of `{+1 if angle < threshold, -1 otherwise}` becomes the score. Scores are then smoothed along the contour by the **TACS contour smoothing** window and binned per pixel into TACS-2 vs TACS-3.

The same loop also accumulates the sum of Gaussian weights at each contour pixel; this is the **density** value used by extended TACS below.

| Class | Color | How it's computed |
|-------|-------|-------------------|
| **TACS-2** | green | Per-contour-pixel score indicates parallel-dominant orientation (fibers run *along* the boundary). The default user interpretation is the benign / healthy organisational pattern. |
| **TACS-3** | orange | Per-contour-pixel score indicates perpendicular-dominant orientation (fibers run *into / out of* the boundary). Associated with invasive collagen architecture (TACS-3 of Provenzano et al., 2006). |
| **TACS-1** | yellow | *Extended TACS only.* After TACS-2/3 are assigned, the per-contour-pixel collagen density is normalised against the densest point along this contour. Pixels with `density_normalised < min_collagen_density` (default 10% of peak) are **reclassified** as TACS-1, i.e. "collagen is present here but sparse compared to the densest region of the same boundary." This matches the original Provenzano TACS-1 concept (sparse collagen). |
| **Unclassified** | not drawn | *Extended TACS only.* Pixels with `density_normalised < min_signal_threshold` (default 2% of peak) are dropped from classification entirely -- not enough nearby collagen to score reliably. No polyline is drawn for these segments. |

Notes:
- TACS-1 / Unclassified only appear when **Enable TACS-1 classification** is on. With it off, every contour pixel is either TACS-2 or TACS-3.
- Density is *relative* to this contour, not absolute -- a uniformly dense boundary will still have TACS-2/3 across all of it, since the densest point sets the reference for 100%.
- Short polyline runs (below **Min polyline length**) are absorbed into the adjacent same-class run before being drawn.
- With **Enable TACS-1 classification** on, the polylines drawn on the boundary are the *reclassified* TACS-1/2/3 segments -- TACS-1 replaces (not overlays) the TACS-2/3 segments it demotes, and Unclassified stretches produce a gap with no polyline.

### Saving results

The results window has two buttons in its title row:

- **Open analysis folder** -- opens the per-image `analysis/perpendicularity/<image_name>/` directory in your OS file manager. Each annotation has its own subfolder named `annotation_<NN>_<annotation_name>/` containing `results.json` (now also stamped with `image_name`, `annotation_name`, and `annotation_index` so the file is self-describing), the foreground-mask PNG, and any saved intermediate masks.
- **Export results as PNG...** -- snapshots the full scrollable results panel (histograms, bars, summary stats for every annotation, foreground-mask thumbnail) into a single PNG. The default save location is the analysis output folder for the current image.

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
