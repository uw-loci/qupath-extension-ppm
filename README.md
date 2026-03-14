# QuPath PPM (Polarized light Microscopy) Extension

[![QuPath Version](https://img.shields.io/badge/qupath-0.6.0+-blue)](https://qupath.github.io/)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](#license)
[![Status](https://img.shields.io/badge/status-pre--release-orange)](#)

> **Part of the QPSC (QuPath Scope Control) system**
> For complete installation instructions and system overview, see: https://github.com/uw-loci/QPSC

## Overview

**qupath-extension-ppm** is a modality plugin for [QuPath](https://qupath.github.io/) and [QPSC](https://github.com/uw-loci/qupath-extension-qpsc) that provides calibration and analysis workflows for **Polarized light Microscopy (PPM)**.

PPM imaging captures multiple polarizer rotation angles per field of view, enabling quantitative analysis of birefringent structures such as collagen fiber orientation, organization, and alignment relative to tissue boundaries.

This extension registers a `PPMModalityHandler` with QPSC's modality plugin system, adding PPM-specific menus, acquisition parameters, and analysis tools to QuPath.

---

## Features

### Calibration Workflows

| Workflow | Description |
|----------|-------------|
| **Polarizer Calibration** | Calibrate the polarizer rotation stage to determine correct angles for optimal birefringence imaging |
| **PPM Rotation Sensitivity Test** | Test rotation stage sensitivity by acquiring images at precise angles; analyzes angular deviation impact on image quality |
| **PPM Birefringence Optimization** | Find the optimal polarizer angle for maximum birefringence signal contrast by systematically testing paired angles |
| **PPM Reference Slide (Sunburst)** | Create a hue-to-angle calibration from a reference slide with radial spoke pattern via linear regression |

### Analysis Workflows

| Workflow | Description |
|----------|-------------|
| **PPM Hue Range Filter** | Real-time overlay highlighting pixels whose fiber angle falls within a user-specified range, using the active calibration |
| **PPM Polarity Plot** | Rose diagram showing fiber angle distribution with circular statistics for selected annotations |
| **Surface Perpendicularity (PS-TACS)** | Analyze fiber orientation relative to annotation boundaries; computes perpendicularity scores along tissue surfaces |
| **Batch PPM Analysis** | Run PPM analysis across all annotations in the current project; exports results as CSV with circular statistics |
| **Back-Propagate Annotations** | Transfer annotations from sub-images back to parent/base images using alignment transforms and XY offsets |

### Acquisition Integration

- Automatic PPM angle sequence loading from microscope configuration
- Decimal precision exposure times (e.g., 1.2ms, 500.0ms, 0.8ms)
- User-customizable angle overrides via the bounding box UI
- Post-processing directory discovery for birefringence and sum images
- Background validation with angle-specific exposure mismatch detection

---

## Requirements

- **QuPath** 0.6.0-rc4 or later
- **qupath-extension-qpsc** 0.3.3 or later (provides ModalityHandler infrastructure)

---

## Installation

1. Download `qupath-extension-ppm-0.1.0-all.jar` from the [latest release](https://github.com/uw-loci/qupath-extension-ppm/releases)
2. Copy the JAR to your QuPath `extensions/` directory
3. Restart QuPath

The PPM extension will automatically register with QPSC and add a **PPM** submenu under the QPSC extensions menu.

---

## Building from Source

Requires Java 21+ and Gradle.

```bash
# QPSC must be published to local Maven first:
cd qupath-extension-qpsc
./gradlew publishToMavenLocal

# Then build PPM:
cd qupath-extension-ppm
./gradlew shadowJar
```

The output JAR is at `build/libs/qupath-extension-ppm-0.1.0-all.jar`.

---

## Package Structure

| Package | Description |
|---------|-------------|
| `qupath.ext.ppm` | Extension entry point (`SetupPPM`) and preferences (`PPMPreferences`) |
| `qupath.ext.ppm.handler` | `PPMModalityHandler`, `RotationManager`, `RotationStrategy` |
| `qupath.ext.ppm.ui` | Dialogs and UI components (angle selection, calibration, bounding box) |
| `qupath.ext.ppm.workflow` | Calibration and optimization acquisition workflows |
| `qupath.ext.ppm.analysis` | Analysis workflows, overlay rendering, batch processing, result export |

---

## How It Works

### Modality Plugin System

QPSC uses a registry-based plugin architecture. During QuPath startup, this extension:

1. Registers `PPMModalityHandler` with `ModalityRegistry` using prefix `"ppm"`
2. Any QPSC modality name starting with `"ppm"` (e.g., `ppm_20x`, `ppm_40x`) routes to this handler
3. The handler contributes menu items, acquisition parameters, and UI components

### PPM Acquisition Sequence

A typical PPM acquisition captures 4 images per tile position:

| Angle | Description |
|-------|-------------|
| 0 deg (crossed) | Baseline crossed polarizer position |
| +theta | Positive rotation angle |
| -theta | Negative rotation angle |
| 45+ deg (uncrossed) | Parallel polarizer reference |

The birefringence signal is computed from the difference between +theta and -theta images.

### Calibration Chain

1. **Polarizer Calibration** -- determines the hardware tick-to-angle mapping
2. **Birefringence Optimization** -- finds the optimal theta for maximum signal
3. **Sunburst Calibration** -- creates a hue-to-orientation-angle mapping (.npz)
4. Analysis workflows use the active calibration for quantitative measurements

---

## License

This project is licensed under the MIT License.
