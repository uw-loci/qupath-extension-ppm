"""
Appose task script for PPM polarity plot (region analysis).

Inputs (injected as Python variables by Appose 0.10.0):
    sum_image:              NDArray (H, W, 3) uint8 RGB sum image
    calibration_path:       str - path to calibration .npz file
    saturation_threshold:   float
    value_threshold:        float
    bins:                   int - histogram bins (e.g. 18 for 10-deg bins)

Optional:
    biref_image:            NDArray (H, W) or (H, W, 3) uint16/uint8
    biref_threshold:        float
    foreground_mask:        NDArray (H, W) uint8
    roi_mask:               NDArray (H, W) uint8

Output:
    task.outputs['result_json'] = JSON string matching CLI output format:
        {histogram_counts, circular_mean, circular_std, resultant_length, n_pixels}
"""
import json
import logging

import numpy as np

logger = logging.getLogger("ppm.appose.polarity")

try:
    from ppm_library.analysis.region_analysis import analyze_region
    from ppm_library.calibration.radial import RadialCalibrationResult

    # Convert NDArrays to numpy arrays
    sum_arr = sum_image.ndarray()

    # Optional inputs
    try:
        biref_arr = biref_image.ndarray()
    except NameError:
        biref_arr = None

    try:
        biref_thresh = biref_threshold
    except NameError:
        biref_thresh = 100.0

    try:
        fg_mask = foreground_mask.ndarray()
    except NameError:
        fg_mask = None

    try:
        roi = roi_mask.ndarray()
    except NameError:
        roi = None

    try:
        min_intensity = int(min_rgb_intensity)
    except NameError:
        min_intensity = 100

    # Load calibration
    calibration = RadialCalibrationResult.load(calibration_path)

    # Build combined foreground mask from biref and/or roi
    combined_mask = None
    if biref_arr is not None:
        from ppm_library.analysis.region_analysis import compute_ppm_positive_mask
        combined_mask = compute_ppm_positive_mask(biref_arr, biref_thresh)

    if fg_mask is not None:
        fg_bool = fg_mask > 0
        if combined_mask is not None:
            combined_mask = combined_mask & fg_bool
        else:
            combined_mask = fg_bool

    # Run region analysis
    result = analyze_region(
        rgb_array=sum_arr,
        calibration=calibration,
        biref_array=biref_arr,
        biref_threshold=biref_thresh,
        saturation_threshold=saturation_threshold,
        value_threshold=value_threshold,
        histogram_bins=bins,
        foreground_mask=fg_mask,
        min_rgb_intensity=min_intensity,
    )

    # Apply ROI mask if provided (restrict to annotation shape)
    if roi is not None:
        roi_bool = roi > 0
        # Re-mask the histogram and stats with ROI
        angles = result['angles']
        mask = result['mask']
        if roi_bool.shape == mask.shape:
            mask = mask & roi_bool
            from ppm_library.analysis.region_analysis import (
                compute_angle_histogram,
                compute_circular_statistics,
            )
            hist = compute_angle_histogram(angles, mask=mask, bins=bins)
            stats = compute_circular_statistics(angles, mask=mask)
            result['histogram'] = hist
            result['stats'] = stats
            result['mask'] = mask

    # Format output to match CLI JSON schema
    class NumpyEncoder(json.JSONEncoder):
        def default(self, obj):
            if isinstance(obj, np.ndarray):
                return obj.tolist()
            if isinstance(obj, (np.integer,)):
                return int(obj)
            if isinstance(obj, (np.floating,)):
                if np.isnan(obj) or np.isinf(obj):
                    return None
                return float(obj)
            if isinstance(obj, np.bool_):
                return bool(obj)
            return super().default(obj)

    output = {
        "histogram_counts": result['histogram']['counts'],
        "circular_mean": result['stats']['circular_mean'],
        "circular_std": result['stats']['circular_std'],
        "resultant_length": result['stats']['resultant_length'],
        "n_pixels": result['stats']['n_pixels'],
    }

    task.outputs['result_json'] = json.dumps(output, cls=NumpyEncoder)
    logger.info("Polarity analysis complete: %d valid pixels", result['stats']['n_pixels'])

except Exception as e:
    logger.error("Polarity analysis failed: %s", e, exc_info=True)
    error_result = {"error": str(e)}
    task.outputs['result_json'] = json.dumps(error_result)
