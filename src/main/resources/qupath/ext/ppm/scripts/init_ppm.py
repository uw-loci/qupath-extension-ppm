"""
Appose worker initialization script for PPM analysis (ppm_library).

Called once via pythonService.init() when the worker process starts.
Sets up persistent globals that remain available across all task() calls.

CRITICAL: All output must go to sys.stderr, NOT sys.stdout.
Appose uses stdout for its JSON-based IPC protocol.
"""
import sys
import os
import logging
import threading
import time

logging.basicConfig(
    level=logging.INFO,
    stream=sys.stderr,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)

logger = logging.getLogger("ppm.appose")

try:
    # Set non-interactive backend before any matplotlib import
    # (ppm_library imports matplotlib for visualization)
    import matplotlib
    matplotlib.use('Agg')

    import numpy
    import scipy
    import skimage
    import ppm_library
    from ppm_library.analysis.region_analysis import analyze_region
    from ppm_library.analysis.surface_analysis import (
        analyze_perpendicularity,
        rasterize_geojson_to_mask,
    )
    from ppm_library.calibration.radial import RadialCalibrationResult

    logger.info("PPM analysis packages loaded successfully")
    logger.info("  ppm_library: %s", getattr(ppm_library, '__version__', 'unknown'))
    logger.info("  numpy: %s", numpy.__version__)
    logger.info("  scipy: %s", scipy.__version__)
    logger.info("  scikit-image: %s", skimage.__version__)

    init_error = None

except Exception as e:
    logger.error("Failed to initialize PPM analysis: %s", e)
    init_error = str(e)


# --- Parent process watcher ---
def _parent_alive(pid):
    """Check if a process with the given PID is still running."""
    if sys.platform == 'win32':
        import ctypes
        kernel32 = ctypes.windll.kernel32
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if handle:
            kernel32.CloseHandle(handle)
            return True
        return False
    else:
        try:
            os.kill(pid, 0)
            return True
        except PermissionError:
            return True
        except OSError:
            return False


def _watch_parent():
    """Exit if parent process (Java/QuPath) dies."""
    ppid = os.getppid()
    if ppid <= 1:
        return
    logger.info("Parent process watcher started (parent PID: %d)", ppid)
    while True:
        time.sleep(3)
        try:
            current_ppid = os.getppid()
            if current_ppid != ppid:
                logger.warning("Parent process changed (%d -> %d), exiting",
                               ppid, current_ppid)
                os._exit(1)
            if not _parent_alive(ppid):
                logger.warning("Parent process %d no longer exists, exiting",
                               ppid)
                os._exit(1)
        except Exception as e:
            logger.debug("Parent watcher check error: %s", e)


_parent_watcher = threading.Thread(target=_watch_parent, daemon=True)
_parent_watcher.start()
