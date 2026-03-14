# PPM Birefringence Optimization

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

Finds the optimal polarizer angle that maximizes birefringence signal contrast. The test systematically acquires paired images at (+theta, -theta) across a range of angles, computes the difference signal for each pair, and identifies the angle that produces the strongest birefringence response.

## When to Run

- After [Polarizer Calibration](polarizer-calibration.md) when setting up a new PPM configuration
- When optimizing image contrast for a specific sample type
- When changing objectives or optical components that may affect the optimal angle

## Prerequisites

1. **Completed Polarizer Calibration** -- rotation stage must be calibrated
2. **Microscope connection** -- QPSC must be connected
3. **Birefringent sample** -- Position on tissue with visible birefringence
4. **Focus** -- Ensure proper focus

## Dialog Settings

### Angle Range

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Min Angle (deg)** | -10.0 | -90 to 0 | Starting angle for the sweep |
| **Max Angle (deg)** | +10.0 | 0 to 90 | Ending angle for the sweep |
| **Step Size (deg)** | 0.1 | 0.01 to 1.0 | Angular increment between test points. Smaller steps give finer resolution but take longer |

The dialog shows a live count of angles and total images to be acquired. A warning appears when total images exceed 400 (which may take considerable time).

### Exposure Settings

Three exposure modes are available:

| Mode | Description | When to Use |
|------|-------------|-------------|
| **Interpolate** (default) | Uses pre-calibrated exposure values from the sensitivity test and interpolates between them | Fastest option. Use when you have a completed sensitivity test |
| **Calibrate** | Measures optimal exposures on a background area first, then acquires on tissue | Most accurate. Use for high-quality results when time is not a constraint |
| **Fixed** | Uses a single exposure time for all angles | Simplest. May saturate at some angles or underexpose at others |

#### Mode-Specific Settings

| Setting | Mode | Default | Description |
|---------|------|---------|-------------|
| **Fixed Exposure (ms)** | Fixed only | 25.0 | Single exposure time applied to all angles |
| **Target Intensity** | Calibrate only | 128 | Target pixel intensity for background calibration (0-255). Values 100-150 are optimal. Above 200 risks saturation |

### Stage Positioning

- **Interpolate and Fixed modes**: Position the stage on tissue with visible birefringence before starting
- **Calibrate mode**: Position on a **blank/background area** first. The system will calibrate exposures there, then prompt you to move to tissue

## Output

- **Optimal angle report** -- The angle that produces maximum birefringence signal
- **Signal vs. angle plot** -- Visualization showing the birefringence response curve
- **Per-angle images** -- All acquired image pairs saved in a timestamped subfolder
- **Results folder** -- Opens automatically after completion with option to browse

## Interpreting Results

The plot shows birefringence signal strength as a function of polarizer angle:

- **Peak location** -- The angle at the maximum is your optimal PPM angle
- **Peak width** -- A broad peak means the system is less sensitive to small angle errors
- **Multiple peaks** -- May indicate optical issues; the primary peak closest to 0 deg is typically preferred
- **Asymmetry** -- A skewed curve may indicate alignment issues

## After Optimization

Update your `config_ppm.yml` with the optimal angle values:
- Set `rotation_angles.positive.tick` to the optimal angle
- Set `rotation_angles.negative.tick` to the negative of the optimal angle
- These will be used for all subsequent PPM acquisitions

## Related Workflows

- [Polarizer Calibration](polarizer-calibration.md) -- Must be completed first
- [PPM Sensitivity Test](ppm-sensitivity-test.md) -- Provides calibrated exposures for interpolate mode
- [PPM Reference Slide](ppm-reference-slide.md) -- Next step: create analysis calibration
