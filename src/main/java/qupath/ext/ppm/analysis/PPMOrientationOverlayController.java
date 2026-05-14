package qupath.ext.ppm.analysis;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;

/**
 * Renders the Python-generated {@code deviation_overlay.png} as a viewer
 * overlay aligned to the analyzed annotation region.
 *
 * <p>Per-annotation overlay PNGs are produced by
 * {@code ppm_library.analysis.surface_analysis.render_orientation_overlay}
 * and saved alongside the rest of that annotation's analysis outputs. The PNG
 * is RGBA with per-pixel alpha (opaque where {@code fiber_mask} is true,
 * transparent elsewhere), so it composites cleanly over the biref image.</p>
 *
 * <p>Mirrors the pattern in the DL pixel classifier's
 * {@code TrainingIssuesOverlayController}: single active overlay, lives in
 * QuPath's custom pixel-layer slot, honors the master opacity slider.</p>
 *
 * <p>Singleton -- only one orientation overlay can be active at a time, since
 * QuPath has a single custom pixel-layer slot per viewer. Toggling on a
 * different annotation replaces the previous overlay.</p>
 */
public final class PPMOrientationOverlayController {

    private static final Logger logger = LoggerFactory.getLogger(PPMOrientationOverlayController.class);

    private static PPMOrientationOverlayController instance;

    public static synchronized PPMOrientationOverlayController getInstance() {
        if (instance == null) {
            instance = new PPMOrientationOverlayController();
        }
        return instance;
    }

    private BufferedImageOverlay currentOverlay;
    private QuPathViewer currentViewer;
    private String currentToken;

    private PPMOrientationOverlayController() {}

    /**
     * Returns the token of the currently-displayed overlay, or null if none.
     * Used by the panel to keep its toggle UI consistent.
     */
    public synchronized String getActiveToken() {
        return currentToken;
    }

    /**
     * Shows the given overlay PNG anchored at the analyzed region's bounds.
     *
     * @param pngPath  absolute path to {@code deviation_overlay.png}
     * @param offsetX  region top-left x in full-res image pixels
     * @param offsetY  region top-left y
     * @param regionW  region width in pixels
     * @param regionH  region height in pixels
     * @param token    caller-supplied identifier (e.g. annotation index or
     *                 directory name) recorded so the panel can tell which
     *                 annotation owns the active overlay
     */
    public void show(Path pngPath, int offsetX, int offsetY, int regionW, int regionH, String token) {
        if (pngPath == null || !Files.exists(pngPath)) {
            logger.warn("Orientation overlay PNG not found: {}", pngPath);
            clear();
            return;
        }
        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath == null) {
            logger.warn("QuPathGUI not available; cannot show orientation overlay");
            return;
        }
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null) {
            logger.warn("No active viewer; cannot show orientation overlay");
            return;
        }

        BufferedImage img;
        try (InputStream in = Files.newInputStream(pngPath)) {
            img = ImageIO.read(in);
        } catch (Exception e) {
            logger.warn("Failed to read orientation overlay PNG {}: {}", pngPath, e.getMessage());
            return;
        }
        if (img == null) {
            logger.warn("ImageIO returned null for orientation overlay PNG {}", pngPath);
            return;
        }

        ImageRegion region = ImageRegion.createInstance(
                offsetX,
                offsetY,
                regionW,
                regionH,
                ImagePlane.getDefaultPlane().getZ(),
                ImagePlane.getDefaultPlane().getT());

        BufferedImageOverlay overlay = new BufferedImageOverlay(qupath.getOverlayOptions(), region, img);

        synchronized (this) {
            currentOverlay = overlay;
            currentViewer = viewer;
            currentToken = token;
        }

        final QuPathViewer v = viewer;
        Platform.runLater(() -> {
            v.setCustomPixelLayerOverlay(overlay);
            v.repaint();
        });

        logger.info(
                "Showing orientation overlay {} at region ({},{}) {}x{}",
                pngPath.getFileName(),
                offsetX,
                offsetY,
                regionW,
                regionH);
    }

    /**
     * Removes the orientation overlay from the viewer, if one is installed by
     * this controller. Safe to call multiple times.
     */
    public void clear() {
        final BufferedImageOverlay overlay;
        final QuPathViewer viewer;
        synchronized (this) {
            overlay = currentOverlay;
            viewer = currentViewer;
            currentOverlay = null;
            currentViewer = null;
            currentToken = null;
        }
        if (overlay != null && viewer != null) {
            Platform.runLater(() -> {
                if (viewer.getCustomPixelLayerOverlay() == overlay) {
                    viewer.resetCustomPixelLayerOverlay();
                    viewer.repaint();
                }
            });
        }
    }
}
