# PPM: Setup and Use

[Back to README](../README.md) | [QPSC Documentation](https://github.com/uw-loci/qupath-extension-qpsc) | [ppm_library Reference](https://github.com/uw-loci/ppm_library)

---

## Purpose

This is the end-to-end overview for Polychromatic Polarization Microscopy (PPM): a
technique where the orientation of birefringent fibers (collagen, muscle, etc.) is
encoded as color (hue) in a single image, letting you turn those colors back into
quantitative fiber angles (Shribak, M. (2015). "Polychromatic polarization microscope:
bringing colors to a colorless world." *Scientific Reports*, 5, 17340.
[DOI: 10.1038/srep17340](https://doi.org/10.1038/srep17340)).

PPM spans **two QuPath extensions**:

- **Hardware and acquisition** live in **qupath-extension-qpsc** -- rotation-stage control,
  calibration workflows, camera/white-balance tools, and the acquisition dialogs. These
  need a connected microscope.
- **Analysis** lives in **this extension (qupath-extension-ppm)** -- turning acquired hues
  into fiber angles, orientation statistics, and boundary-relative scoring. These run on
  any workstation with no microscope.

This page ties the pieces together as a walkthrough and links out to the detailed guide for
each step. It does not replace those guides -- follow the links for parameters, dialog
settings, and troubleshooting.

## The Big Picture

The journey from "I have a PPM microscope" to quantitative fiber-orientation results:

1. **One-time hardware calibration** (needs the microscope; repeat only when optics move)
   - [Polarizer Calibration](polarizer-calibration.md) -- required before any acquisition
   - [PPM Birefringence Optimization](ppm-birefringence-optimization.md) -- find the best +/- angle for your sample
   - [PPM Reference Slide (Sunburst)](ppm-reference-slide.md) -- required before any analysis
   - Optional: [PPM Rotation Sensitivity Test](ppm-sensitivity-test.md) -- quality-check the rotation stage
2. **Per-session prep** (camera / white balance / background)
   - White balance and flat-field background collection for good PPM images
3. **Acquisition** -- run a normal QPSC acquisition with PPM selected as the modality
4. **Analysis** -- measure fiber orientation with the PPM Analysis tools

Sections below expand each stage.

---

## 1. One-Time Hardware Calibration

These workflows are provided by QPSC and appear under the **Scope > PPM** menu when a
microscope with a PPM modality is configured. They need a connected microscope but only
need to be re-run when the optics are physically moved.

### Polarizer Calibration (required before acquisition)

**Menu:** Scope > PPM > Polarizer Calibration (PPM)...

Calibrates the polarizer rotation stage to determine the correct hardware tick values for
the four standard PPM angles. It sweeps the rotation stage, fits a sine curve to
intensity-vs-angle, and reports the recommended tick values to put in `config_ppm.yml`
under `rotation_angles`. **This must be done before any PPM acquisition**, and only needs
repeating when the polarizer or analyzer is physically moved.

See: [Polarizer Calibration](polarizer-calibration.md)

### Birefringence Optimization (recommended)

**Menu:** Scope > PPM > PPM Birefringence Optimization...

Sweeps paired (+theta, -theta) angles across a range, computes the difference signal for
each pair, and identifies the angle that produces the strongest birefringence response for
your sample type. Update `rotation_angles.positive.tick` / `negative.tick` in
`config_ppm.yml` with the result.

See: [PPM Birefringence Optimization](ppm-birefringence-optimization.md)

### Rotation Sensitivity Test (optional QA)

**Menu:** Scope > PPM > PPM Rotation Sensitivity Test...

Acquires at the standard angles plus small angular offsets and reports how sensitive the
birefringence signal is to angular error -- a quality-control step confirming the rotation
hardware is precise enough for quantitative PPM.

See: [PPM Rotation Sensitivity Test](ppm-sensitivity-test.md)

### Sunburst / Reference Slide Calibration (required before analysis)

**Menu:** Scope > PPM > PPM Reference Slide...

Creates a hue-to-angle calibration from a sunburst (radial spoke) reference slide. It
samples HSV hue along radial lines and fits `angle = slope * hue + intercept`, saving a
`.npz` calibration file. **Every PPM analysis workflow requires this `.npz`.** Run it after
Polarizer Calibration (and, ideally, Birefringence Optimization).

> Avoid pixel saturation when capturing the reference slide -- clipped pixels have corrupted
> hue values and will produce an incorrect calibration. Use low-angle (crossed / near-crossed)
> settings and check the Live Viewer histogram before running.

The sunburst calibration maps hue to orientation angle and is **objective-independent** -- a
calibration captured at one objective (e.g. 40x) is valid at any other (e.g. 20x). A higher
magnification yields a better-quality calibration, but the hue-to-angle relationship itself
does not change with magnification.

See: [PPM Reference Slide (Sunburst Calibration)](ppm-reference-slide.md)

---

## 2. Camera, White Balance, and Background

Good PPM images depend on correct **per-angle** exposure/white balance and flat-field
background correction, because transmitted light intensity varies dramatically with
polarizer orientation (crossed polarizers transmit very little light; uncrossed transmit
the maximum). These tools live in QPSC.

> **Menu-path note:** After a recent reorganization, most image-quality tools live under
> **Extensions > QP Scope > Utilities > Image Quality**. Camera Control remains a top-level
> item. The JAI White Balance tool appears only when a JAI 3-CCD camera is detected.

| Tool | Menu | What it does |
|------|------|--------------|
| Camera Control | Extensions > QP Scope > Camera Control... | View/test exposure and gain; per-angle cards with an Apply button that sets camera settings and rotates the polarizer. Useful for JAI white-balance troubleshooting. |
| JAI White Balance Calibration | Extensions > QP Scope > Utilities > Image Quality > JAI Camera > White Balance... | Calibrate per-channel (R/G/B) exposure times for JAI 3-CCD prism cameras. Supports Simple (single angle) and PPM (4 angles) modes. Results are saved to YAML and used automatically. |
| WB Comparison Test | Extensions > QP Scope > Utilities > Image Quality > WB Comparison Test... | Acquire the same region under multiple white-balance modes (Off, Camera AWB, Simple, Per-angle PPM) and compare side by side. |
| Collect Background Images | Extensions > QP Scope > Utilities > Image Quality > Collect Background Images | Capture flat-field correction images with adaptive exposure. Pick the **White Balance Mode** the backgrounds are for (Off / Camera AWB / Simple / Per-angle PPM) so acquisition picks up the matching set. |

**Per-angle white balance:** For JAI 3-CCD cameras, each polarization angle can be white-
balanced independently. Calibrate with the **PPM White Balance (4 Angles)** mode in JAI
White Balance Calibration, then collect backgrounds in the matching **Per-angle PPM** white
balance mode. At acquisition time, select the same per-angle PPM white-balance mode so the
correct backgrounds are applied.

Detailed guides (qpsc repo):

- [White Balance Calibration](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/tools/white-balance-calibration.md)
- [Camera Control](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/tools/camera-control.md)
- [Background Collection](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/tools/background-collection.md)
- [WB Comparison Test](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/tools/wb-comparison-test.md)

---

## 3. The Four Standard PPM Angles

A typical PPM acquisition captures multiple images per tile position, each at a different
polarizer rotation angle. The standard 4-angle set (with typical exposures) is:

| Name | Ticks | Degrees | Typical Exposure | Purpose |
|------|-------|---------|------------------|---------|
| negative | -7.0 | -14 deg | 500 ms | Birefringence signal (negative offset) |
| crossed | 0.0 | 0 deg | 800 ms | Crossed polarizers (minimum transmission) |
| positive | 7.0 | +14 deg | 500 ms | Birefringence signal (positive offset) |
| uncrossed | 90.0 | 180 deg | 10 ms | Uncrossed polarizers (maximum transmission) |

**Per-angle exposure.** Each angle has an independent exposure time because crossed
polarizers transmit very little light (long exposure) while uncrossed transmit the maximum
(short exposure). Exposure is resolved non-interactively at acquisition time, checking, in
order: (1) the exposures used when background was collected, (2) the per-objective /
per-detector profile in `imageprocessing_PPM.yml`, (3) persistent PPM preference defaults,
and (4) hardcoded fallbacks (500 / 800 / 500 / 10 ms). Ticks are hardware units (degrees of
rotation-stage travel) determined by Polarizer Calibration.

**How the images are used.** The birefringence image is computed from the difference of the
positive and negative angles; the crossed image shows extinction; the uncrossed image
provides a brightfield-like reference. Post-processing produces `.biref` (channel name "PPM
Subtracted") and `.sum` (channel name "PPM Sum") derived images that the analysis workflows
read.

Full developer reference:
[PPM Modality Implementation](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/developer/PPM_MODALITY.md)

---

## 4. Acquisition

PPM is not a separate acquisition dialog -- it is selected as a **modality** during a normal
QPSC acquisition. Use either of the standard acquisition entry points:

- **Bounded Acquisition** -- Extensions > QP Scope > Bounded Acquisition (direct
  bounding-box acquisition from stage coordinates)
- **Acquire from Existing Image** -- Extensions > QP Scope > Acquire from Existing Image
  (targeted acquisition from annotations on an existing image)

In the acquisition dialog:

- Set the **Modality** to a PPM modality (e.g. `ppm`).
- Choose the **White Balance Mode** matching your calibration and backgrounds -- for full
  PPM this is the **Per-angle (PPM)** mode.
- The **PPM angle controls** (part of the acquisition dialog for the PPM modality) let you
  choose which of the four angles to acquire via the negative / crossed / positive /
  uncrossed checkboxes, and optionally override the +/- angles for one experiment without
  editing config files. Crossed (0) and uncrossed (90) are always preserved as reference
  positions; the override only affects the positive and negative angles. Angle selection and
  overrides are set upfront -- there is no per-image angle popup.

Before acquisition, QPSC validates that background images exist for each selected angle and
were collected at matching exposure and white-balance mode; it warns (and disables
background correction only for affected angles) on a mismatch.

For the general acquisition walkthrough and dialog details, see the qpsc guides:

- [Acquisition Wizard](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/tools/acquisition-wizard.md)
- [QPSC Workflows](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/WORKFLOWS.md)

---

## 5. Analysis

After acquisition, PPM analysis runs in **this extension** under **Extensions > PPM Analysis**
-- no microscope required. **All analysis tools require the Sunburst calibration `.npz`**
(Step 1) to map hue to fiber angle.

| Tool | Menu | What it does |
|------|------|--------------|
| PPM Hue Range Filter | Extensions > PPM Analysis > PPM Hue Range Filter... | Live overlay highlighting pixels whose fiber angle falls in a chosen range. See [guide](ppm-hue-range-filter.md). |
| PPM Polarity Plot | Extensions > PPM Analysis > PPM Polarity Plot... | Rose/polar diagram of fiber orientation for an annotation, with circular statistics (mean angle, dispersion, resultant length). See [guide](ppm-polarity-plot.md). |
| Surface Perpendicularity (PS-TACS) | Extensions > PPM Analysis > Surface Perpendicularity... | Score fiber orientation relative to a boundary you draw (parallel vs. perpendicular). See [guide](surface-perpendicularity.md). |
| Batch PPM Analysis | Extensions > PPM Analysis > Batch PPM Analysis... | Run polarity and/or perpendicularity across all annotations in a project and export CSV. See [guide](batch-ppm-analysis.md). |

Related project tool:

- [Back-Propagate Annotations](back-propagate-annotations.md) -- push annotations drawn on a
  high-resolution sub-image back onto the parent whole-slide image.

---

## Typical First-Time Checklist

1. Connect QuPath (QPSC) to the microscope server and confirm the PPM modality is configured.
2. **[Polarizer Calibration](polarizer-calibration.md)** (Scope > PPM) -- required before acquisition.
3. *(Optional)* **[PPM Rotation Sensitivity Test](ppm-sensitivity-test.md)** -- verify rotation precision.
4. **[PPM Birefringence Optimization](ppm-birefringence-optimization.md)** -- find the best +/- angle for your sample; update `config_ppm.yml`.
5. Calibrate **white balance** (JAI: PPM 4-angle mode) and **collect backgrounds** in the matching per-angle PPM white-balance mode.
6. **[PPM Reference Slide (Sunburst)](ppm-reference-slide.md)** -- required before analysis; produces the `.npz`.
7. Run a **Bounded** or **Existing Image** acquisition with Modality = PPM and the per-angle PPM white-balance mode; choose your angles.
8. Analyze under **Extensions > PPM Analysis** (Hue Range Filter, Polarity Plot, Surface Perpendicularity, Batch).

---

## See Also

- [PPM Analysis README](../README.md) -- feature list and the hardware-vs-analysis split
- [QPSC Utilities Reference](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/UTILITIES.md) -- all QPSC tools, including the PPM hardware menu
- [PPM Modality Implementation (developer)](https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/developer/PPM_MODALITY.md)
- [ppm_library](https://github.com/uw-loci/ppm_library) -- the Python library behind the calibration and analysis math
