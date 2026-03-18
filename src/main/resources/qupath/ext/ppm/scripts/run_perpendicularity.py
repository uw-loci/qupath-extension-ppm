"""
Appose task script for PPM surface perpendicularity analysis.

Inputs (injected as Python variables by Appose 0.10.0):
    sum_image:              NDArray (H, W, 3) uint8 RGB sum image
    calibration_path:       str - path to calibration .npz file
    geojson_boundary:       str - GeoJSON string for annotation boundary
    pixel_size_um:          float
    dilation_um:            float
    zone_mode:              str ("outside", "inside", or "both")
    tacs_threshold:         float (degrees)
    fill_holes:             bool
    saturation_threshold:   float
    value_threshold:        float
    image_width:            int - width of the region
    image_height:           int - height of the region

Optional:
    biref_image:            NDArray (H, W) or (H, W, 3) uint16/uint8
    biref_threshold:        float
    foreground_mask:        NDArray (H, W) uint8
    output_dir:             str - directory for saving intermediate results

Output:
    task.outputs['result_json'] = JSON string (same format as CLI output)
"""
import json
import logging
import sys
import tempfile
import os

import numpy as np
from appose import NDArray as PyNDArray

logger = logging.getLogger("ppm.appose.perpendicularity")

try:
    from ppm_library.analysis.surface_analysis import (
        analyze_perpendicularity,
        rasterize_geojson_to_mask,
    )
    from ppm_library.analysis.region_analysis import (
        compute_angles_from_rgb,
        compute_ppm_positive_mask,
    )
    from ppm_library.calibration.radial import RadialCalibrationResult

    # Convert NDArrays to numpy arrays
    sum_arr = sum_image.ndarray()

    # Optional inputs with defaults (Appose 0.10.0 injects as variables)
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
        out_dir = output_dir
    except NameError:
        out_dir = None

    # Load calibration from file
    calibration = RadialCalibrationResult.load(calibration_path)

    # Rasterize GeoJSON boundary to mask
    # rasterize_geojson_to_mask accepts dict or file path
    geojson_dict = json.loads(geojson_boundary)
    boundary_mask = rasterize_geojson_to_mask(
        geojson_dict, image_width, image_height, fill_holes=fill_holes
    )

    # Run analysis
    result = analyze_perpendicularity(
        rgb_array=sum_arr,
        calibration=calibration,
        boundary_mask=boundary_mask,
        dilation_um=dilation_um,
        pixel_size_um=pixel_size_um,
        mode=zone_mode,
        fill_holes=fill_holes,
        tacs_threshold_deg=tacs_threshold,
        biref_array=biref_arr,
        biref_threshold=biref_thresh,
        saturation_threshold=saturation_threshold,
        value_threshold=value_threshold,
        foreground_mask=fg_mask,
    )

    # Compute diagnostic mask statistics so the user can understand
    # how thresholds are affecting the analysis
    total_pixels = sum_arr.shape[0] * sum_arr.shape[1]

    # HSV-valid mask (same computation as inside analyze_perpendicularity)
    hsv_result = compute_angles_from_rgb(
        sum_arr, calibration,
        saturation_threshold=saturation_threshold,
        value_threshold=value_threshold,
    )
    hsv_valid_count = int(np.count_nonzero(hsv_result['valid_mask']))

    biref_valid_count = -1  # sentinel: not applicable
    if biref_arr is not None:
        biref_mask = compute_ppm_positive_mask(biref_arr, biref_thresh)
        biref_valid_count = int(np.count_nonzero(biref_mask))
        combined_mask = hsv_result['valid_mask'] & biref_mask
    elif fg_mask is not None:
        combined_mask = hsv_result['valid_mask'] & fg_mask.astype(bool)
    else:
        combined_mask = hsv_result['valid_mask']

    combined_valid_count = int(np.count_nonzero(combined_mask))

    # Rasterize boundary to compute zone stats
    zone_valid_count = combined_valid_count  # approximation before zone mask

    # Add diagnostics to result
    result['mask_diagnostics'] = {
        'total_pixels': total_pixels,
        'hsv_valid_pixels': hsv_valid_count,
        'biref_valid_pixels': biref_valid_count,
        'combined_valid_pixels': combined_valid_count,
    }

    # Return the combined foreground mask as an NDArray for visualization
    mask_uint8 = (combined_mask.astype(np.uint8) * 255)
    h_mask, w_mask = mask_uint8.shape
    mask_nd = PyNDArray(dtype="uint8", shape=[h_mask, w_mask])
    np.copyto(mask_nd.ndarray(), mask_uint8)
    task.outputs['foreground_mask'] = mask_nd

    logger.info(
        "Mask stats: total=%d, hsv_valid=%d, biref_valid=%s, combined=%d",
        total_pixels, hsv_valid_count,
        biref_valid_count if biref_valid_count >= 0 else "N/A",
        combined_valid_count,
    )

    # Convert result to JSON-serializable format
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

    # Remove large arrays that aren't needed in JSON output
    # (deviation_angles is per-pixel, too large for JSON transfer)
    if 'simple' in result and 'deviation_angles' in result['simple']:
        del result['simple']['deviation_angles']

    result_json = json.dumps(result, cls=NumpyEncoder)
    task.outputs['result_json'] = result_json

    logger.info("Perpendicularity analysis complete")

except Exception as e:
    logger.error("Perpendicularity analysis failed: %s", e, exc_info=True)
    error_result = {"error": str(e)}
    task.outputs['result_json'] = json.dumps(error_result)
