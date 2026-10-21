# Changelog

All notable changes to the QuPath PPM Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.4] - 2026-10-21

### Changed
- **Built against qupath-extension-qpsc 0.11.0** (was 0.10.0). PPM compiles against QPSC classes, so it is built against the release users run; CI and the release build check out qpsc v0.11.0 and tiles-to-pyramid v0.7.1.
- **The Appose plumbing is the copy shared with the other Appose extensions** (ApposeSupport, ApposeTaskRunner, ApposeEnvLocation, ApposeArrays, ApposeGpu). A task that fails with a dead worker or "thread death" before it has reported progress is retried once on a replacement worker. The environment pins appose 0.12, the release with the thread-death fix, and the lock is regenerated for it.

### Fixed
- **Dialog titles are unique, so saved window sizes no longer collide.** The Dialog Position Manager keys saved geometry on the window title. An error or progress popup that shared a content window's title overwrote that window's saved size when it closed, and two different content dialogs with one title overwrote each other. Popups now carry a suffix (- Error, - Confirm, - Progress, - Result).
- **The catalog notification reads the jar URL under either field name.** The dispatch died with "No .jar asset found on release v0.2.3" while the jar was on the release, because `gh release view` names the field differently from the release webhook.

## [0.2.3] - 2026-09-20

### Fixed
- **A dead Appose worker no longer fails silently.** The analysis workflows recover from a worker that has died, run without a network connection, and report failures instead of swallowing them.
- **Back-propagation follows QPSC's baked parity** rather than deriving orientation itself, so propagated objects land correctly on stage-inverted scopes.

### Changed
- **Built against qupath-extension-qpsc 0.10.0** (was 0.9.0), the current release. PPM compiles against QPSC classes, so it must be built against the version users actually run.
- **Requires QuPath 0.7.0** (was declared as 0.6.0).
- **The Appose environment location is configurable**, and each extension names itself in its own environment-cleanup dialog, so it is clear which extension is asking.

### Added
- **Documented the biref-mask provenance columns** and what a window-analysis failure means.
- **Automated releases.** Tagging `vX.Y.Z` now builds the shadow jar and publishes the release; previously the jar was built by hand on a maintainer's machine.

## [0.2.2] - 2026-07-23

### Changed
- Appose (the embedded Python environment manager for the PPM analysis workflows) bumped from 0.10.0 to 0.12.0. No analysis behavior changes.

## [0.2.1] - 2026-06-09

## [0.2.0] - 2026-05-27

### Changed
- QuPath 0.7.0 / Java 25; analysis-only (hardware code lives in qupath-extension-qpsc).
