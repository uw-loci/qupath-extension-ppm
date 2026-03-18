# PPM Hue Range Filter

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Applies a real-time overlay to the current PPM image that highlights all pixels whose fiber orientation angle falls within a user-specified range. This interactive tool helps visualize the spatial distribution of fiber orientations and identify regions of interest for further analysis.

## Prerequisites

1. **Completed [Sunburst Calibration](ppm-reference-slide.md)** -- The hue-to-angle calibration (.npz) must exist. This maps HSV hue values to fiber orientation angles.
2. **PPM birefringence or sum image open** -- Open a post-processed PPM image in QuPath (not a raw angle image). The image should have been acquired with low-angle (crossed/near-crossed) polarizer settings and without pixel saturation, as overexposed pixels have corrupted hue values.

## How It Works

1. The calibration file maps hue values to orientation angles
2. For each pixel, the HSV hue is converted to an orientation angle using the calibration
3. Pixels whose angle falls within the specified range **and** pass the saturation/value thresholds are highlighted with the overlay color
4. The overlay updates in real time as you adjust the sliders

## Control Panel Settings

The control panel appears as a non-modal window, so you can interact with QuPath while it is open.

### Angle Range

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Angle Low (deg)** | 0 | 0-180 | Lower bound of the angle range to highlight |
| **Angle High (deg)** | 180 | 0-180 | Upper bound of the angle range to highlight |

Fiber orientations are measured in the range 0-180 deg, where 0 deg is horizontal and 90 deg is vertical.

### Threshold Settings

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Saturation Threshold** | 0.1 | 0-1 | Minimum HSV saturation for a pixel to be considered valid. Filters out grayscale/low-color pixels that don't have a meaningful orientation |
| **Value Threshold** | 0.1 | 0-1 | Minimum HSV brightness for a pixel to be considered valid. Filters out dark pixels |

### Display Settings

| Setting | Default | Description |
|---------|---------|-------------|
| **Overlay Color** | Red | Color used to highlight matching pixels. Click to open a color picker |
| **Opacity** | 0.5 | Transparency of the overlay (0 = fully transparent, 1 = fully opaque) |

### Statistics

The panel displays a live count of matching pixels out of total valid pixels, giving a quick sense of what fraction of the tissue has fiber orientations in the selected range.

### Clear Overlay

Click **Clear Overlay** to remove the hue range filter and restore the normal image view.

## Tips

- **Narrow the range** to find fibers at a specific orientation (e.g., 85-95 deg for near-vertical fibers)
- **Sweep the range** across 0-180 deg to get an intuitive feel for the fiber angle distribution
- **Adjust thresholds** if too many background pixels are highlighted (raise thresholds) or if fibers are missed (lower thresholds)
- Use in combination with [PPM Polarity Plot](ppm-polarity-plot.md) to see both spatial and statistical views of fiber orientation

## Related Workflows

- [PPM Reference Slide](ppm-reference-slide.md) -- Creates the calibration required for angle mapping
- [PPM Polarity Plot](ppm-polarity-plot.md) -- Statistical view of orientation distribution
- [Surface Perpendicularity](surface-perpendicularity.md) -- Orientation relative to tissue boundaries
