# QuPath PPM (Polychromatic Polarization Microscopy) Extension

[![QuPath Version](https://img.shields.io/badge/qupath-0.7.0+-blue)](https://qupath.github.io/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](#license)
[![Status](https://img.shields.io/badge/status-pre--release-orange)](#)

> **Part of the QPSC (QuPath Scope Control) system**
> For complete installation instructions and system overview, see: https://github.com/uw-loci/QPSC

## Overview

**qupath-extension-ppm** is a [QuPath](https://qupath.github.io/) extension for measuring **collagen fiber direction and organization in tissue** from polarized-light microscopy images.

It works with images from **Polychromatic Polarization Microscopy (PPM)**, a technique where the orientation of birefringent fibers (collagen, muscle, etc.) is encoded as color (hue) in a single image. This extension turns those colors back into quantitative fiber angles, lets you map fiber direction across whole slides, compare alignment between regions, and score how fibers run relative to tissue boundaries -- relevant for fibrosis, tumor stroma, cardiac remodeling, and developmental tissue studies.

Analysis menus appear under **Extensions > PPM Analysis** and run on any workstation -- no microscope required -- so the same tools work for offline reanalysis of existing PPM datasets.

![Overview of whole-slide PPM analyses in QuPath: per-microscope hue-to-orientation calibration (sunburst plot), collagen orientation by border region (parallel vs perpendicular fibers on the edges of an ROI), dominant orientation by axial circular statistics on a 15 um grid, and polarity judged from PPM pixel color.](documentation/images/ppm-analysis-overview.png)

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
| **Polarizer Calibration** | Set up the microscope so fiber colors map correctly to fiber angles -- the foundation step before any quantitative orientation analysis (polarizer rotation stage calibration) | [docs](documentation/polarizer-calibration.md) |
| **PPM Rotation Sensitivity Test** | Check how much small mechanical errors in the rotation stage affect your measurements -- a quality-control step for the optical setup (angular deviation analysis) | [docs](documentation/ppm-sensitivity-test.md) |
| **PPM Birefringence Optimization** | Find the polarizer setting that gives the strongest, cleanest fiber signal for your sample type, so faint structures stay visible (paired-angle contrast search) | [docs](documentation/ppm-birefringence-optimization.md) |
| **PPM Reference Slide (Sunburst)** | Build the color-to-angle lookup from a known radial test pattern, so hues in your tissue images can be read as real fiber directions in degrees (hue-to-angle calibration via linear regression) | [docs](documentation/ppm-reference-slide.md) |

### Analysis Workflows (in this extension, no microscope needed)

| Workflow | Description | Guide |
|----------|-------------|-------|
| **PPM Hue Range Filter** | Highlight every region where fibers point in a chosen direction -- e.g. show only fibers running roughly horizontal -- as a live overlay you can sweep through (fiber-angle range overlay using the active calibration) | [docs](documentation/ppm-hue-range-filter.md) |
| **PPM Polarity Plot** | See whether fibers in an annotation are aligned in one direction or randomly oriented, with a rose/polar diagram and summary alignment statistics (circular statistics: mean angle, dispersion, resultant length) | [docs](documentation/ppm-polarity-plot.md) |
| **Surface Perpendicularity (PS-TACS)** | Score how fibers run relative to a tissue boundary you draw -- parallel along the edge vs. pointing into it -- useful for tumor-stroma TACS-style analysis and basement-membrane work (perpendicularity score along annotation contours) | [docs](documentation/surface-perpendicularity.md) |
| **Batch PPM Analysis** | Run the same fiber-orientation analysis across every annotation in a project in one pass and export a CSV ready for stats software (project-wide circular statistics export) | [docs](documentation/batch-ppm-analysis.md) |
| **Back-Propagate Annotations** | Draw regions on a high-resolution sub-image (e.g. an ROI scan) and push those annotations back onto the parent whole-slide image automatically (alignment-transform + XY-offset propagation) | [docs](documentation/back-propagate-annotations.md) |

### Acquisition Integration (provided by QPSC)

> These features are part of QPSC's PPM hardware handler (`modality/ppm/PPMModalityHandler`), not this extension.

- Acquire PPM image sets without manually entering polarizer angles every time -- the angle sequence is read straight from the microscope configuration
- Use realistic exposure times for faint or strong signals -- exposures support sub-millisecond decimal precision (e.g. 1.2 ms, 500.0 ms, 0.8 ms)
- Override the default acquisition angles for one experiment without editing config files, directly in the bounding-box dialog
- Pick up the right derived images automatically after acquisition (birefringence and sum images), so analysis workflows know where to look
- Catch background-correction problems early -- the system warns you if your background images were taken at different exposures than your data (angle-specific exposure mismatch detection)

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

### Prerequisites

The PPM extension requires **qupath-extension-qpsc** to be installed first. All analysis workflows (hue range filter, polarity plot, perpendicularity, batch analysis, back-propagation) depend on shared utilities provided by QPSC.

### Installation Steps

1. **Install QPSC first** (if not already installed):
   - Via extension catalog: Open **Extensions > Manage extensions**, add `https://github.com/uw-loci/qupath-catalog-qpsc`, and install "QuPath Scope (QPSC)"
   - Or download directly from [uw-loci/qupath-extension-qpsc/releases](https://github.com/uw-loci/qupath-extension-qpsc/releases) and copy the JAR to your `extensions/` directory

2. Download `qupath-extension-ppm-0.2.0-all.jar` from the [latest release](https://github.com/uw-loci/qupath-extension-ppm/releases)
3. Copy the JAR to your QuPath `extensions/` directory
4. Restart QuPath

The PPM extension will add a **PPM Analysis** submenu under QuPath's **Extensions** menu. No microscope connection is required for analysis workflows.

**If QPSC is missing:** When you launch QuPath without QPSC, a dialog will appear with instructions on how to install it. You can also click **Extensions > PPM Analysis > Install QPSC extension (required)...** at any time to see the installation instructions.

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

## Support

For general support and feature requests, please post on the [image.sc forum](https://forum.image.sc/) with the `#qupath` tag and mention `@Mike_Nelson` to flag the topic for my attention.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## AI-Assisted Development

This project was developed with assistance from [Claude](https://claude.ai) (Anthropic). Claude was used as a development tool for code generation, architecture design, debugging, and documentation throughout the project.
