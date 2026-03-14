# Back-Propagate Annotations

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Transfers annotations from sub-images (acquired via QPSC's Existing Image workflow) back to the parent/base image, transforming coordinates through the alignment chain. This allows analysis performed on high-resolution sub-acquisitions to be visualized and compared in the context of the original overview image.

## When to Use

- After drawing or generating annotations on PPM sub-images (e.g., tumor boundaries, collagen regions)
- When you want to see all annotations from multiple sub-acquisitions overlaid on a single parent image
- To consolidate results from [Batch PPM Analysis](batch-ppm-analysis.md) onto the overview image

## Prerequisites

1. **Multi-sample project** -- A QPSC project with sub-images acquired via the Existing Image workflow
2. **Alignment data** -- Sub-images must have alignment transforms and XY offset metadata (set automatically during acquisition)
3. **Classified annotations** -- Annotations on sub-images must have a classification (PathClass) assigned

## How It Works

The coordinate transform chain for each annotation:

```
sub-image pixels  -->  stage microns  -->  flipped parent pixels  -->  original base pixels
     (pixel size + XY offset)    (inverse alignment)         (flip transform)
```

1. **Discovery** -- Finds image collections with both sub-images and parent images
2. **Class collection** -- Discovers all annotation classes across sub-images
3. **Configuration** -- User selects target image and classes to propagate
4. **Transformation** -- Each annotation is transformed through the coordinate chain
5. **Insertion** -- Transformed annotations are added to the target image and saved

## Dialog Settings

### Target Image

| Option | Description |
|--------|-------------|
| **Original base image** (default) | Propagate to the original (non-flipped) macro image. Applies both the alignment inverse and flip correction |
| **Flipped XY parent** | Propagate to the flipped version of the parent image. Only applies the alignment inverse |

If any collection is missing the original base image, a note appears on the Original option.

### Annotation Classes

- All discovered annotation classes are listed with checkboxes (all selected by default)
- Use **Check All** / **Check None** to quickly toggle selections
- Only annotations with a selected class will be propagated

### Options

| Option | Default | Description |
|--------|---------|-------------|
| **Include measurements** | Enabled | Copy measurement values from the source annotations to the propagated copies |
| **Lock propagated annotations** | Enabled | Lock the propagated annotations to prevent accidental editing. Recommended since these are derived data |

## Output

- **Propagated annotations** -- Appear on the target image with names prefixed by the source acquisition region (e.g., "Region_1: Tumor boundary")
- **Preserved classification** -- All PathClass assignments are maintained
- **Preserved measurements** -- If enabled, all measurement values are copied
- **Summary notification** -- Shows how many annotations were successfully propagated

## Important Notes

- Annotations without a classification (PathClass) are **not propagated** -- classify them first
- The transform depends on correct alignment metadata -- if the alignment was poor, propagated annotations may be offset
- Propagated annotations are **copies** -- editing or deleting them does not affect the originals on sub-images
- The workflow processes all sub-images in a collection automatically

## Troubleshooting

| Problem | Likely Cause | Solution |
|---------|-------------|----------|
| No collections found | Missing metadata | Ensure sub-images were acquired via QPSC's Existing Image workflow |
| No classified annotations | Annotations not classified | Assign PathClass to annotations on sub-images |
| Annotations offset on target | Poor alignment | Re-run Microscope Alignment workflow in QPSC |
| "No alignment transform" warning | Missing sample alignment | Ensure alignment was completed for this sample |

## Related Workflows

- [Batch PPM Analysis](batch-ppm-analysis.md) -- Generate analysis annotations to propagate
- [Surface Perpendicularity](surface-perpendicularity.md) -- Analysis that produces annotations suitable for propagation
- [QPSC Existing Image Acquisition](https://github.com/uw-loci/qupath-extension-qpsc) -- Creates the sub-images and alignment data
