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

logger = logging.getLogger("ppm.appose.perpendicularity")

try:
    from ppm_library.analysis.surface_analysis import (
        analyze_perpendicularity,
        rasterize_geojson_to_mask,
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
