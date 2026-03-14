# Polarizer Calibration (PPM)

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Calibrates the polarizer rotation stage to determine the correct hardware tick values for the four standard PPM angles: crossed (0 deg), positive, negative, and uncrossed. This calibration is required before any PPM acquisition and only needs to be repeated when the optics are physically repositioned.

## When to Run

- **First-time setup** of a PPM microscope system
- After **physically moving or realigning** the polarizer or analyzer
- If acquired PPM images show unexpected birefringence behavior

You do **not** need to recalibrate between sessions if the hardware has not been moved.

## Prerequisites

1. **Microscope connection** -- QPSC must be connected to the microscope server
2. **Clear field of view** -- Position the stage on a **uniform, bright background** (e.g., empty slide area with transmitted light). The calibration measures intensity as a function of rotation angle, so a uniform field gives the cleanest sine curve
3. **Focus** -- Ensure the sample plane is in focus (Kohler illumination recommended)

## How It Works

1. The system sweeps the rotation stage through a range of angles
2. At each angle, an image is acquired and the mean intensity is measured
3. A **sine curve** is fitted to the intensity-vs-angle data
4. The minima of the sine curve correspond to **crossed polarizer positions** (extinction)
5. The system identifies the optimal tick values for each of the four PPM angles

## Dialog Settings

| Setting | Default | Description |
|---------|---------|-------------|
| **Output Folder** | Config directory | Where the calibration report and data files are saved |
| **Step Size (deg)** | 0.5 | Angular step between measurements. Smaller steps give higher precision but take longer. Range: 0.5-10.0 deg |
| **Sweep Range** | Full rotation | The range of angles to sweep. Typically a full 180-degree sweep |

## Output

The calibration produces:

- **Text report** -- Detailed results with recommended `config_ppm.yml` values
- **Intensity plot** -- Sine curve fit showing intensity vs. rotation angle
- **Recommended tick values** -- The hardware tick values to set in `config_ppm.yml` for `rotation_angles`

## After Calibration

1. Review the report -- check that the R-squared value of the sine fit is high (> 0.95)
2. Update your `config_ppm.yml` with the recommended tick values
3. The new values will be used automatically for all subsequent PPM acquisitions

## Troubleshooting

| Problem | Likely Cause | Solution |
|---------|-------------|----------|
| Noisy sine curve | Non-uniform background | Reposition on a cleaner area |
| Poor R-squared | Debris or bubbles in the field | Clean the slide and retry |
| Unexpected minima count | Polarizer not rotating smoothly | Check the rotation stage mechanism |
| Very low intensity at all angles | Analyzer missing or misaligned | Verify the optical path |

## Related Workflows

- [PPM Sensitivity Test](ppm-sensitivity-test.md) -- Tests the precision of rotation angles after calibration
- [PPM Birefringence Optimization](ppm-birefringence-optimization.md) -- Finds the optimal angle for maximum birefringence signal
- [PPM Reference Slide](ppm-reference-slide.md) -- Creates hue-to-angle calibration for analysis
