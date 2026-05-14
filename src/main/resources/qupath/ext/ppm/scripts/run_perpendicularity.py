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
    biref_blur_sigma:       float - pre-blur on biref image before threshold (default 0)
    hsv_blur_sigma:         float - pre-blur on RGB before HSV/intensity validity (default 0)

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
        compute_window_alignment,
        rasterize_geojson_to_mask,
        render_orientation_overlay,
        render_window_alignment_overlay,
        render_window_orientation_overlay,
        save_pixel_arrays,
        save_window_metrics,
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

    try:
        ext_tacs = bool(extended_tacs)
    except NameError:
        ext_tacs = False

    try:
        min_density = float(min_collagen_density)
    except NameError:
        min_density = 0.1

    try:
        min_signal = float(min_signal_threshold)
    except NameError:
        min_signal = 0.02

    try:
        biref_blur = float(biref_blur_sigma)
    except NameError:
        biref_blur = 0.0

    try:
        hsv_blur = float(hsv_blur_sigma)
    except NameError:
        hsv_blur = 0.0

    try:
        win_enabled = bool(window_analysis_enabled)
    except NameError:
        win_enabled = False

    try:
        win_um = float(window_size_um)
    except NameError:
        win_um = 15.0

    try:
        win_overlap_pct = float(window_overlap_percent)
    except NameError:
        win_overlap_pct = 0.0

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
        extended_tacs=ext_tacs,
        min_collagen_density=min_density,
        min_signal_threshold=min_signal,
        biref_blur_sigma=biref_blur,
        hsv_blur_sigma=hsv_blur,
    )

    # Persist per-pixel arrays and render the orientation overlay BEFORE
    # popping the intermediate keys for JSON serialization.
    if out_dir:
        try:
            save_pixel_arrays(result, out_dir)
            simple_block = result.get('simple') or {}
            dev_arr = simple_block.get('deviation_angles')
            if dev_arr is not None and result.get('fiber_mask') is not None:
                overlay_path = os.path.join(str(out_dir), 'deviation_overlay.png')
                render_orientation_overlay(
                    dev_arr,
                    result['fiber_mask'],
                    overlay_path,
                    cmap_name='seismic',
                )
                logger.info('Wrote deviation_overlay.png to %s', out_dir)
        except Exception as render_err:
            logger.warning('Failed to save per-pixel arrays or overlay: %s', render_err)

    # Moving-window alignment analysis (extension beyond the PS-TACS paper).
    # Aggregates per-pixel fiber orientations into a grid of windows so we can
    # show alignment + dominant orientation as heatmaps and optionally emit
    # per-window PathObjects on the Java side.
    window_summary = None
    if win_enabled and result.get('fiber_angles') is not None and result.get('fiber_mask') is not None:
        try:
            window_px = max(2, int(round(win_um / float(pixel_size_um))))
            overlap_frac = max(0.0, min(0.95, win_overlap_pct / 100.0))
            stride_px = max(1, int(round(window_px * (1.0 - overlap_frac))))
            window_metrics = compute_window_alignment(
                result['fiber_angles'], result['fiber_mask'], window_px, stride_px
            )
            window_metrics['window_um'] = float(win_um)
            if out_dir:
                save_window_metrics(window_metrics, out_dir)
                h_full = result['fiber_angles'].shape[0]
                w_full = result['fiber_angles'].shape[1]
                render_window_alignment_overlay(
                    window_metrics, os.path.join(str(out_dir), 'alignment_overlay.png'), h_full, w_full
                )
                render_window_orientation_overlay(
                    window_metrics, os.path.join(str(out_dir), 'orientation_overlay.png'), h_full, w_full
                )
                logger.info(
                    'Wrote window analysis (%dx%d grid, window=%dpx, stride=%dpx) to %s',
                    window_metrics['grid_shape'][0],
                    window_metrics['grid_shape'][1],
                    window_px,
                    stride_px,
                    out_dir,
                )
            # Summary stats for the JSON payload (small enough to send back).
            op = window_metrics['order_parameter']
            n_nonempty = int(np.count_nonzero(~np.isnan(op)))
            mean_os = float(np.nanmean(op)) if n_nonempty > 0 else float('nan')
            window_summary = {
                'enabled': True,
                'window_um': float(win_um),
                'window_px': int(window_px),
                'stride_px': int(stride_px),
                'overlap_percent': float(win_overlap_pct),
                'grid_h': int(window_metrics['grid_shape'][0]),
                'grid_w': int(window_metrics['grid_shape'][1]),
                'n_windows_total': int(window_metrics['grid_shape'][0] * window_metrics['grid_shape'][1]),
                'n_windows_nonempty': n_nonempty,
                'mean_order_parameter': mean_os,
            }
        except Exception as window_err:
            logger.warning('Window analysis failed: %s', window_err)
            window_summary = {'enabled': True, 'error': str(window_err)}
    if window_summary is not None:
        result['window_analysis'] = window_summary

    # Use diagnostic counts and intermediate masks returned by
    # analyze_perpendicularity (avoids recomputing angles, masks, zones)
    from scipy import ndimage as ndi
    from skimage.morphology import remove_small_objects

    diag = result.pop('mask_diagnostics')
    fiber_mask = result.pop('fiber_mask')
    zone_mask = result.pop('zone_mask')
    # Drop the other large per-pixel arrays so they don't bloat the JSON
    # payload to Java. They were already saved to disk above when out_dir
    # was set.
    result.pop('fiber_angles', None)
    result.pop('dist_from_boundary', None)

    # Build analysis mask from the returned intermediate masks
    analysis_mask = fiber_mask & zone_mask
    analysis_valid_count = int(np.count_nonzero(analysis_mask))

    # Gaussian smooth + re-threshold to clean up noisy fragments
    if mask_sigma > 0:
        smoothed = ndi.gaussian_filter(analysis_mask.astype(np.float32), sigma=mask_sigma)
        analysis_mask = smoothed > 0.3

    # Remove small connected components (area threshold)
    if min_area > 0:
        analysis_mask = remove_small_objects(analysis_mask, max_size=min_area)

    final_valid_count = int(np.count_nonzero(analysis_mask))

    # Add visualization-specific counts to the diagnostics from analysis
    diag['analysis_valid_pixels'] = analysis_valid_count
    diag['final_pixels_after_cleanup'] = final_valid_count
    result['mask_diagnostics'] = diag

    # Return the analysis mask as an NDArray for visualization
    mask_uint8 = (analysis_mask.astype(np.uint8) * 255)
    h_mask, w_mask = mask_uint8.shape
    mask_nd = PyNDArray(dtype="uint8", shape=[h_mask, w_mask])
    np.copyto(mask_nd.ndarray(), mask_uint8)
    task.outputs['foreground_mask'] = mask_nd

    logger.info(
        "Mask stats: total=%d, hsv=%d, biref=%s, combined=%d, "
        "zone=%d, analysis=%d, final=%d",
        diag['total_pixels'], diag['hsv_valid_pixels'],
        diag['biref_valid_pixels'] if diag['biref_valid_pixels'] >= 0 else "N/A",
        diag['combined_valid_pixels'],
        diag['zone_pixels'],
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
    # (deviation_angles + normal_angle_deg are per-pixel, too large for JSON
    # transfer). They were already saved to disk when out_dir was set.
    if 'simple' in result and 'deviation_angles' in result['simple']:
        del result['simple']['deviation_angles']
    if 'simple' in result and 'normal_angle_deg' in result['simple']:
        del result['simple']['normal_angle_deg']

    result_json = json.dumps(result, cls=NumpyEncoder)
    task.outputs['result_json'] = result_json

    logger.info("Perpendicularity analysis complete")

except Exception as e:
    logger.error("Perpendicularity analysis failed: %s", e, exc_info=True)
    error_result = {"error": str(e)}
    task.outputs['result_json'] = json.dumps(error_result)
