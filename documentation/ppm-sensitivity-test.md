# PPM Rotation Sensitivity Test

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Tests the rotation stage sensitivity by acquiring images at precisely controlled angles and analyzing the impact of angular deviations on image quality and birefringence calculations. This validation step confirms that your rotation hardware can reliably achieve the angular precision required for quantitative PPM imaging.

## When to Run

- After [Polarizer Calibration](polarizer-calibration.md) to verify the calibration quality
- When investigating unexpectedly noisy birefringence results
- After hardware maintenance on the rotation stage
- As part of a quality assurance protocol

## Prerequisites

1. **Completed Polarizer Calibration** -- The rotation stage must be calibrated first
2. **Microscope connection** -- QPSC must be connected to the microscope server
3. **Birefringent sample** -- Position on tissue or material with visible birefringence (e.g., collagen fibers)
4. **Focus** -- Ensure proper focus on the sample

## How It Works

1. The system acquires images at the standard PPM angles plus small angular offsets
2. For each offset, birefringence is computed from the paired +theta/-theta images
3. The results show how sensitive the birefringence signal is to angular errors
4. A comprehensive report is generated with plots and statistical analysis

## Dialog Settings

| Setting | Default | Description |
|---------|---------|-------------|
| **Output Folder** | Config directory | Where test results and reports are saved |
| **Test Angles** | Standard PPM set | The set of angles around which to test sensitivity |
| **Angular Offsets** | +/- 0.1, 0.5, 1.0 deg | The deviations to test at each angle |

## Output

- **Analysis report** -- Text summary with signal-to-noise metrics per angular offset
- **Sensitivity plots** -- Visualization of birefringence signal vs. angular deviation
- **Image series** -- Acquired images at each test angle (saved in output folder)
- **Recommendations** -- Whether the rotation precision is sufficient for PPM

## Interpreting Results

- **High sensitivity** (large signal change with small angle change) means your rotation stage precision is critical -- ensure the hardware is well-maintained
- **Low sensitivity** (stable signal across offsets) means your system is robust to small angular errors
- A good system should show consistent birefringence values within +/- 0.5 deg of the calibrated angle

## Related Workflows

- [Polarizer Calibration](polarizer-calibration.md) -- Must be completed first
- [PPM Birefringence Optimization](ppm-birefringence-optimization.md) -- Finds the optimal angle for maximum signal
