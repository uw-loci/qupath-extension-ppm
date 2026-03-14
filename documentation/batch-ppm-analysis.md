# Batch PPM Analysis

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Runs PPM analysis across all (or selected) annotations in the current QuPath project, exporting results as CSV files with circular statistics per annotation. Supports both polarity analysis and surface perpendicularity analysis in a single batch run.

## Prerequisites

1. **Active PPM calibration** -- A sunburst calibration must be set (see [PPM Reference Slide](ppm-reference-slide.md))
2. **QuPath project open** -- With PPM images and annotations
3. **Annotations drawn** -- Regions of interest must be annotated in the PPM images

## How It Works

1. **Discovery** -- Scans the project for PPM analysis sets (images with birefringence data and annotations)
2. **Selection** -- User selects which image sets to include and which analysis types to run
3. **Processing** -- For each selected image, runs the chosen analyses on all annotations
4. **Export** -- Writes results to CSV files and adds measurements to QuPath's measurement tables

## Configuration Panel

### Image Set Selection

The panel shows all discovered PPM analysis sets in the project. Each set corresponds to a PPM image with annotations.

| Control | Description |
|---------|-------------|
| **Image set checkboxes** | Check/uncheck individual image sets to include |
| **Check All** | Select all discovered image sets |
| **Check None** | Deselect all image sets |

### Analysis Types

| Analysis | Default | Description |
|----------|---------|-------------|
| **Polarity** | Enabled | Generates polarity plots with fiber orientation histograms and circular statistics (mean direction, circular std dev, resultant length) for each annotation |
| **Perpendicularity** | Disabled | Computes fiber perpendicularity relative to an annotation boundary. Requires additional parameters (see below) |

### Perpendicularity Parameters

These are enabled only when the Perpendicularity checkbox is checked:

| Parameter | Default | Description |
|-----------|---------|-------------|
| **Boundary Class** | (select from list) | The annotation class that defines the tissue boundary. The dropdown is populated from annotation classes present in the project |
| **Dilation (um)** | varies | Distance from boundary to analyze, in micrometers. Defines the width of the analysis zone |
| **Zone Mode** | "Inside" | Which side of the boundary to analyze: "Inside" analyzes toward the tissue interior, "Outside" analyzes away from the boundary |
| **PS-TACS Threshold (deg)** | varies | Angle threshold for classifying fibers as perpendicular. Deviation angles above this threshold are counted as perpendicular |
| **Fill Holes** | enabled | Fill holes in boundary annotations before computing boundary normals. Recommended for annotations with interior holes |

## Output

### CSV Files

Results are saved as CSV files in the project directory:

| File | Contents |
|------|----------|
| **polarity_results.csv** | Per-annotation circular statistics: mean angle, circular std dev, resultant length, valid pixel count |
| **perpendicularity_results.csv** | Per-annotation perpendicularity scores: mean deviation, parallel/oblique/perpendicular fractions, PS-TACS score |

### QuPath Measurements

Analysis results are also added to QuPath's annotation measurement tables, making them available for:
- Viewing in the Measurements tab
- Exporting via QuPath's built-in export tools
- Scripting and further analysis

## Tips

- **Start with Polarity only** to get a quick overview of fiber organization across the project
- **Add Perpendicularity** when you need boundary-relative analysis (requires properly drawn boundary annotations)
- Use QuPath's classification system to organize annotations by tissue type before running batch analysis

## Related Workflows

- [PPM Polarity Plot](ppm-polarity-plot.md) -- Interactive single-annotation version
- [Surface Perpendicularity](surface-perpendicularity.md) -- Interactive single-annotation version
- [Back-Propagate Annotations](back-propagate-annotations.md) -- Transfer results to parent images
