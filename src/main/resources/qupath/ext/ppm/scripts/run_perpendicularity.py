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
    boundary_smoothing_sigma: float - Gaussian sigma for boundary smoothing (default 5.0)
    smoothing_window:       int - TACS contour score smoothing window (default 10)
    min_collagen_area:      int - min connected component area in pixels (default 100)
    mask_smoothing_sigma:   float - Gaussian sigma for mask cleanup (default 2.0)

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

    try:
        bnd_sigma = float(boundary_smoothing_sigma)
    except NameError:
        bnd_sigma = 5.0

    try:
        smooth_win = int(smoothing_window)
    except NameError:
        smooth_win = 10

    try:
        min_area = int(min_collagen_area)
    except NameError:
        min_area = 100

    try:
        mask_sigma = float(mask_smoothing_sigma)
    except NameError:
        mask_sigma = 2.0

    try:
        min_intensity = int(min_rgb_intensity)
    except NameError:
        min_intensity = 100

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
        smoothing_window=smooth_win,
        boundary_smoothing_sigma=bnd_sigma,
        biref_array=biref_arr,
        biref_threshold=biref_thresh,
        saturation_threshold=saturation_threshold,
        value_threshold=value_threshold,
        foreground_mask=fg_mask,
        min_rgb_intensity=min_intensity,
    )

    # Compute diagnostic mask statistics so the user can understand
    # how thresholds are affecting the analysis
    from scipy import ndimage as ndi
    from ppm_library.analysis.surface_analysis import compute_border_zone_mask
    from skimage.morphology import remove_small_objects

    total_pixels = sum_arr.shape[0] * sum_arr.shape[1]

    # HSV-valid mask (same computation as inside analyze_perpendicularity)
    hsv_result = compute_angles_from_rgb(
        sum_arr, calibration,
        saturation_threshold=saturation_threshold,
        value_threshold=value_threshold,
        exclude_clipped=True,
        min_rgb_intensity=min_intensity,
    )
    hsv_valid_count = int(np.count_nonzero(hsv_result['valid_mask']))
    clipped_count = hsv_result.get('n_clipped', 0)
    dark_count = hsv_result.get('n_dark_excluded', 0)

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

    # Compute the zone mask (same as analyze_perpendicularity does internally)
    # so the returned mask shows ONLY what was actually analyzed
    mask_for_zone = boundary_mask.copy()
    if fill_holes:
        mask_for_zone = ndi.binary_fill_holes(mask_for_zone)

    dilation_px_int = max(1, int(round(dilation_um / pixel_size_um)))
    zone_result = compute_border_zone_mask(
        mask_for_zone, dilation_px_int, mode=zone_mode, fill_holes=False
    )
    zone_mask = zone_result['zone_mask']

    # Intersect foreground with zone -> only pixels that were actually analyzed
    analysis_mask = combined_mask & zone_mask
    analysis_valid_count = int(np.count_nonzero(analysis_mask))

    # Gaussian smooth + re-threshold to clean up noisy fragments
    if mask_sigma > 0:
        smoothed = ndi.gaussian_filter(analysis_mask.astype(np.float32), sigma=mask_sigma)
        analysis_mask = smoothed > 0.3

    # Remove small connected components (area threshold)
    if min_area > 0:
        analysis_mask = remove_small_objects(analysis_mask, min_size=min_area)

    final_valid_count = int(np.count_nonzero(analysis_mask))

    # Get clipping count from the main result too
    result_clipped = result.get('n_clipped_pixels', 0)

    # Add diagnostics to result
    result['mask_diagnostics'] = {
        'total_pixels': total_pixels,
        'hsv_valid_pixels': hsv_valid_count,
        'clipped_pixels': clipped_count if clipped_count > 0 else result_clipped,
        'dark_excluded_pixels': dark_count,
        'biref_valid_pixels': biref_valid_count,
        'combined_valid_pixels': combined_valid_count,
        'zone_pixels': int(np.count_nonzero(zone_mask)),
        'analysis_valid_pixels': analysis_valid_count,
        'final_pixels_after_cleanup': final_valid_count,
    }

    # Return the analysis mask as an NDArray for visualization
    mask_uint8 = (analysis_mask.astype(np.uint8) * 255)
    h_mask, w_mask = mask_uint8.shape
    mask_nd = PyNDArray(dtype="uint8", shape=[h_mask, w_mask])
    np.copyto(mask_nd.ndarray(), mask_uint8)
    task.outputs['foreground_mask'] = mask_nd

    logger.info(
        "Mask stats: total=%d, hsv=%d, biref=%s, combined=%d, "
        "zone=%d, analysis=%d, final=%d",
        total_pixels, hsv_valid_count,
        biref_valid_count if biref_valid_count >= 0 else "N/A",
        combined_valid_count,
        int(np.count_nonzero(zone_mask)),
        analysis_valid_count,
        final_valid_count,
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
