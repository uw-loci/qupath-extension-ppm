# QuPath PPM (Polychromatic Polarization Microscopy) Extension

[![QuPath Version](https://img.shields.io/badge/qupath-0.7.0+-blue)](https://qupath.github.io/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](#license)
[![Status](https://img.shields.io/badge/status-pre--release-orange)](#)

> **Part of the QPSC (QuPath Scope Control) system**
> For complete installation instructions and system overview, see: https://github.com/uw-loci/QPSC

## Overview

**qupath-extension-ppm** is an **analysis extension** for [QuPath](https://qupath.github.io/) that provides image analysis and processing workflows for **Polychromatic Polarization Microscopy (PPM)**.

PPM imaging captures multiple polarizer rotation angles per field of view, producing color-encoded images where hue represents fiber orientation. This enables quantitative analysis of birefringent structures such as collagen fiber orientation, organization, and alignment relative to tissue boundaries.

This extension adds analysis menus under **Extensions > PPM Analysis** in QuPath. Analysis menus are always available, even on workstations without microscope hardware -- making it safe to install for offline image analysis.

> **Hardware/acquisition workflows** (rotation control, calibration, angle selection) are provided by [qupath-extension-qpsc](https://github.com/uw-loci/qupath-extension-qpsc) in its `modality/ppm/` package. This extension does NOT register a modality handler or contain any hardware code.

> **Reference:** Shribak, M. (2015). "Polychromatic polarization microscope: bringing colors to a colorless world." *Scientific Reports*, 5, 17340. [DOI: 10.1038/srep17340](https://doi.org/10.1038/srep17340)

### Related Repositories

| Repository | Description |
|------------|-------------|
| [qupath-extension-qpsc](https://github.com/uw-loci/qupath-extension-qpsc) | Main QPSC extension -- provides PPM hardware/acquisition (rotation, calibration, angle selection) plus ModalityHandler infrastructure, microscope communication, and coordinate transformations |
| [ppm_library](https://github.com/uw-loci/ppm_library) | Python image processing library for PPM -- provides radial calibration, birefringence computation, background correction, and circular statistics used by this extension's workflows |
| [QPSC](https://github.com/uw-loci/QPSC) | System overview and installation guide for the complete QPSC platform |

---

## Contents

- [Features](#features)
- [Workflow Documentation](#workflow-documentation)
- [Requirements](#requirements)
- [Installation](#installation)
- [Building from Source](#building-from-source)
- [Package Structure](#package-structure)
- [How It Works](#how-it-works)
- [License](#license)

---

## Features

### Calibration Workflows (in QPSC, require microscope)

> These workflows are provided by [qupath-extension-qpsc](https://github.com/uw-loci/qupath-extension-qpsc) and appear under the **Scope > PPM** menu when a microscope is configured. They are documented here for the complete PPM workflow reference.

| Workflow | Description | Guide |
|----------|-------------|-------|
| **Polarizer Calibration** | Calibrate the polarizer rotation stage to determine correct angles for optimal birefringence imaging | [docs](documentation/polarizer-calibration.md) |
| **PPM Rotation Sensitivity Test** | Test rotation stage sensitivity by acquiring images at precise angles; analyzes angular deviation impact on image quality | [docs](documentation/ppm-sensitivity-test.md) |
| **PPM Birefringence Optimization** | Find the optimal polarizer angle for maximum birefringence signal contrast by systematically testing paired angles | [docs](documentation/ppm-birefringence-optimization.md) |
| **PPM Reference Slide (Sunburst)** | Create a hue-to-angle calibration from a reference slide with radial spoke pattern via linear regression | [docs](documentation/ppm-reference-slide.md) |

### Analysis Workflows (in this extension, no microscope needed)

| Workflow | Description | Guide |
|----------|-------------|-------|
| **PPM Hue Range Filter** | Real-time overlay highlighting pixels whose fiber angle falls within a user-specified range, using the active calibration | [docs](documentation/ppm-hue-range-filter.md) |
| **PPM Polarity Plot** | Rose diagram showing fiber angle distribution with circular statistics for selected annotations | [docs](documentation/ppm-polarity-plot.md) |
| **Surface Perpendicularity (PS-TACS)** | Analyze fiber orientation relative to annotation boundaries; computes perpendicularity scores along tissue surfaces | [docs](documentation/surface-perpendicularity.md) |
| **Batch PPM Analysis** | Run PPM analysis across all annotations in the current project; exports results as CSV with circular statistics | [docs](documentation/batch-ppm-analysis.md) |
| **Back-Propagate Annotations** | Transfer annotations from sub-images back to parent/base images using alignment transforms and XY offsets | [docs](documentation/back-propagate-annotations.md) |

### Acquisition Integration (provided by QPSC)

> These features are part of QPSC's PPM hardware handler (`modality/ppm/PPMModalityHandler`), not this extension.

- Automatic PPM angle sequence loading from microscope configuration
- Decimal precision exposure times (e.g., 1.2ms, 500.0ms, 0.8ms)
- User-customizable angle overrides via the bounding box UI
- Post-processing directory discovery for birefringence and sum images
- Background validation with angle-specific exposure mismatch detection

---

## Workflow Documentation

Detailed guides for each workflow are in the [`documentation/`](documentation/) folder. Each guide includes prerequisites, step-by-step instructions, parameter descriptions, and troubleshooting tips.

All dialogs include a **?** help button that opens the corresponding documentation page in your browser.

### Recommended Workflow Order

For first-time PPM setup, follow this sequence:

1. **[Polarizer Calibration](documentation/polarizer-calibration.md)** -- Find crossed polarizer positions (one-time hardware setup)
2. **[PPM Sensitivity Test](documentation/ppm-sensitivity-test.md)** -- Validate rotation precision (optional but recommended)
3. **[PPM Birefringence Optimization](documentation/ppm-birefringence-optimization.md)** -- Find optimal angle for your sample type
4. **[PPM Reference Slide](documentation/ppm-reference-slide.md)** -- Create hue-to-angle calibration for analysis

Once calibrated, the analysis workflows are available:

5. **[PPM Hue Range Filter](documentation/ppm-hue-range-filter.md)** -- Interactive angle visualization
6. **[PPM Polarity Plot](documentation/ppm-polarity-plot.md)** -- Orientation statistics per annotation
7. **[Surface Perpendicularity](documentation/surface-perpendicularity.md)** -- Boundary-relative analysis
8. **[Batch PPM Analysis](documentation/batch-ppm-analysis.md)** -- Project-wide batch processing
9. **[Back-Propagate Annotations](documentation/back-propagate-annotations.md)** -- Transfer results to parent images

---

## Requirements

- **QuPath** 0.7.0 or later (Java 25+)
- **[qupath-extension-qpsc](https://github.com/uw-loci/qupath-extension-qpsc)** 0.5.0 or later (provides ImageMetadataManager, DocumentationHelper, and shared utilities)
- **[ppm_library](https://github.com/uw-loci/ppm_library)** (Python, called via subprocess for calibration and analysis computations)

---

## Installation

1. Download `qupath-extension-ppm-0.2.0-all.jar` from the [latest release](https://github.com/uw-loci/qupath-extension-ppm/releases)
2. Copy the JAR to your QuPath `extensions/` directory
3. Restart QuPath

The PPM extension will add a **PPM Analysis** submenu under QuPath's **Extensions** menu. No microscope connection is required for analysis workflows.

---

## Building from Source

Requires Java 25+ and Gradle.

```bash
# QPSC must be published to local Maven first:
cd qupath-extension-qpsc
./gradlew publishToMavenLocal

# Then build PPM:
cd qupath-extension-ppm
./gradlew shadowJar
```

The output JAR is at `build/libs/qupath-extension-ppm-0.2.0-all.jar`.

---

## Package Structure

| Package | Description |
|---------|-------------|
| `qupath.ext.ppm` | Extension entry point (`SetupPPM`) and analysis preferences (`PPMPreferences`) |
| `qupath.ext.ppm.analysis` | Analysis workflows, overlay rendering, batch processing, result export, PPM image set discovery |
| `qupath.ext.ppm.service` | `ApposePPMService` -- Python/Appose integration for ppm_library |
| `qupath.ext.ppm.ui` | `SetupEnvironmentDialog`, `PythonConsoleWindow` -- analysis utility UI |

> **Note:** Hardware packages (`handler/`, `workflow/`) were moved to QPSC's `modality/ppm/` package in v0.4.2 (2026-04-16). PPM hardware preferences (angles, exposures, overrides, calibration path) are in QPSC's `PPMPreferences`. This extension's `PPMPreferences` holds only analysis thresholds (birefringence, histogram bins, saturation, TACS).

---

## How It Works

### Architecture: Hardware vs Analysis Split

PPM functionality is split across two extensions:

- **QPSC** (`modality/ppm/`): Owns all hardware -- `PPMModalityHandler` (registered with `ModalityRegistry`), rotation control (`RotationManager`), calibration workflows, angle selection dialog, exposure management, and hardware preferences. These appear under **Scope > PPM** when a microscope is configured.
- **This extension**: Owns all analysis -- hue range filtering, polarity plots, perpendicularity analysis, batch processing, and Appose/Python integration. These appear under **Extensions > PPM Analysis** and are always available.

### PPM Acquisition Sequence

A typical PPM acquisition captures 4 images per tile position:

| Angle | Description |
|-------|-------------|
| 0 deg (crossed) | Baseline crossed polarizer position |
| +theta | Positive rotation angle |
| -theta | Negative rotation angle |
| ~90 deg (uncrossed) | Parallel polarizer reference |

The birefringence signal is computed from the difference between +theta and -theta images.

### Calibration Chain

1. **[Polarizer Calibration](documentation/polarizer-calibration.md)** -- determines the hardware tick-to-angle mapping
2. **[Birefringence Optimization](documentation/ppm-birefringence-optimization.md)** -- finds the optimal theta for maximum signal
3. **[Sunburst Calibration](documentation/ppm-reference-slide.md)** -- creates a hue-to-orientation-angle mapping (.npz)
4. Analysis workflows use the active calibration for quantitative measurements

### Python Integration

The analysis workflows call [ppm_library](https://github.com/uw-loci/ppm_library) (Python) for computationally intensive operations:
- **Radial calibration** -- Sunburst spoke detection and linear regression
- **Birefringence computation** -- Multi-angle difference imaging
- **Circular statistics** -- Mean direction, circular standard deviation, resultant length
- **Surface analysis** -- Boundary normal computation and perpendicularity scoring

Java handles QuPath I/O (reading image regions, annotations, GeoJSON export, results display) while Python handles the numerical computation. Communication is via temporary file exchange and subprocess calls.

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## AI-Assisted Development

This project was developed with assistance from [Claude](https://claude.ai) (Anthropic). Claude was used as a development tool for code generation, architecture design, debugging, and documentation throughout the project.
