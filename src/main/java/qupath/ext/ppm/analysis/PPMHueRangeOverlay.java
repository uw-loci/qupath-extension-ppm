package qupath.ext.ppm.analysis;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * View-based overlay that highlights pixels within a user-specified fiber angle range.
 *
 * <p>Computes the overlay dynamically for the currently visible region at the viewer's
 * downsample level, similar to QuPath's built-in channel overlays. Only the pixels
 * on screen are ever loaded and processed, so this works efficiently with any image
 * size including whole-slide images.</p>
 *
 * <p>When the viewer pans or zooms, the overlay recomputes for the new visible region.
 * When filter parameters change, the overlay recomputes for the same region with
 * updated settings. A stale cached overlay is shown during parameter transitions
 * for a smooth visual experience.</p>
 */
public class PPMHueRangeOverlay extends AbstractOverlay {

    private static final Logger logger = LoggerFactory.getLogger(PPMHueRangeOverlay.class);

    // Filter parameters (set from FX thread, read from compute/paint threads)
    private volatile float angleLow = 0;
    private volatile float angleHigh = 180;
    private volatile float saturationThreshold = 0.2f;
    private volatile float valueThreshold = 0.2f;
    private volatile int highlightRGB = 0x00FF00;
    private volatile int minRgbIntensity = 100;
    private volatile boolean active = false;

    // Cached computation result (single volatile swap for thread safety)
    private volatile CachedResult cache;

    // Cancellation: newer computations supersede older ones
    private final AtomicLong computationId = new AtomicLong(0);

    // Dedicated background thread for overlay computation
    private final ExecutorService computeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PPM-HueRange-Compute");
        t.setDaemon(true);
        return t;
    });

    // Resources
    private PPMCalibration calibration;
    private ImageServer<BufferedImage> server;
    private final QuPathViewer viewer;
    private StatsListener statsListener;

    /** Callback for overlay statistics (matching/total pixels in visible area). */
    public interface StatsListener {
        void onStatsUpdated(int matchingPixels, int totalValidPixels);
    }

    /** Immutable snapshot of a computed overlay for a specific region and parameters. */
    private static class CachedResult {
        final BufferedImage image;
        final int regionX, regionY, regionW, regionH;
        final double downsample;
        final long paramHash;
        final int matching;
        final int valid;

        CachedResult(
                BufferedImage image, ImageRegion region, double downsample, long paramHash, int matching, int valid) {
            this.image = image;
            this.regionX = region.getX();
            this.regionY = region.getY();
            this.regionW = region.getWidth();
            this.regionH = region.getHeight();
            this.downsample = downsample;
            this.paramHash = paramHash;
            this.matching = matching;
            this.valid = valid;
        }

        boolean matchesRegion(ImageRegion r, double ds) {
            return regionX == r.getX()
                    && regionY == r.getY()
                    && regionW == r.getWidth()
                    && regionH == r.getHeight()
                    && Double.compare(downsample, ds) == 0;
        }

        boolean matchesFully(ImageRegion r, double ds, long ph) {
            return matchesRegion(r, ds) && paramHash == ph;
        }
    }

    public PPMHueRangeOverlay(QuPathViewer viewer) {
        super(viewer.getOverlayOptions());
        this.viewer = viewer;
    }

    public void setCalibration(PPMCalibration calibration) {
        this.calibration = calibration;
    }

    public void setServer(ImageServer<BufferedImage> server) {
        this.server = server;
    }

    public void setStatsListener(StatsListener listener) {
        this.statsListener = listener;
    }

    public void setAngleRange(float low, float high) {
        this.angleLow = low;
        this.angleHigh = high;
    }

    public void setSaturationThreshold(float threshold) {
        this.saturationThreshold = threshold;
    }

    public void setValueThreshold(float threshold) {
        this.valueThreshold = threshold;
    }

    public void setHighlightRGB(int rgb) {
        this.highlightRGB = rgb & 0xFFFFFF;
    }

    public void setMinRgbIntensity(int threshold) {
        this.minRgbIntensity = threshold;
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            cache = null;
            viewer.repaint();
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Triggers a viewer repaint, which causes paintOverlay to recompute if needed.
     * This replaces the old pre-computation approach.
     */
    public void recompute() {
        if (active) {
            viewer.repaint();
        }
    }

    @Override
    public void paintOverlay(
            Graphics2D g2d,
            ImageRegion region,
            double downsample,
            ImageData<BufferedImage> imageData,
            boolean isSelected) {
        if (!active || calibration == null || server == null || region == null) return;

        long paramHash = computeParamHash();
        CachedResult cached = this.cache;

        if (cached != null && cached.matchesFully(region, downsample, paramHash)) {
            // Perfect cache hit - draw immediately
            drawOverlay(g2d, cached);
            return;
        }

        if (cached != null && cached.matchesRegion(region, downsample)) {
            // Region matches but params changed - show stale overlay for smooth transition
            drawOverlay(g2d, cached);
        }
        // If region changed, don't draw stale overlay (would be at wrong position)

        // Start async computation for the new region/params
        long id = computationId.incrementAndGet();
        ImageRegion regionCopy = ImageRegion.createInstance(
                region.getX(), region.getY(), region.getWidth(), region.getHeight(), region.getZ(), region.getT());

        computeExecutor.submit(() -> {
            try {
                computeForRegion(id, regionCopy, downsample, paramHash);
            } catch (Exception e) {
                if (id == computationId.get()) {
                    logger.error("Failed to compute hue range overlay", e);
                }
            }
        });
    }

    private void drawOverlay(Graphics2D g2d, CachedResult cached) {
        Composite old = g2d.getComposite();
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) getOpacity()));
        g2d.drawImage(cached.image, cached.regionX, cached.regionY, cached.regionW, cached.regionH, null);
        g2d.setComposite(old);
    }

    private void computeForRegion(long id, ImageRegion region, double downsample, long paramHash) throws IOException {
        if (id != computationId.get()) return;

        RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, region);
        BufferedImage img = server.readRegion(request);
        int w = img.getWidth();
        int h = img.getHeight();

        if (id != computationId.get()) return;

        // Snapshot current parameters
        float lo = this.angleLow;
        float hi = this.angleHigh;
        float satTh = this.saturationThreshold;
        float valTh = this.valueThreshold;
        int highlight = 0xFF000000 | this.highlightRGB;

        BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        float[] hsb = new float[3];
        int matching = 0;
        int valid = 0;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                Color.RGBtoHSB(r, g, b, hsb);

                int maxRgb = Math.max(r, Math.max(g, b));
                if (hsb[1] >= satTh && hsb[2] >= valTh && maxRgb >= minRgbIntensity) {
                    valid++;
                    float angle = (float) calibration.hueToAngle(hsb[0]);

                    boolean inRange;
                    if (lo <= hi) {
                        inRange = angle >= lo && angle <= hi;
                    } else {
                        // Wrap-around range (e.g., 170-10 degrees)
                        inRange = angle >= lo || angle <= hi;
                    }

                    if (inRange) {
                        matching++;
                        overlay.setRGB(px, py, highlight);
                    }
                }
            }
            // Check cancellation periodically
            if (py % 100 == 0 && id != computationId.get()) return;
        }

        // Atomically update cache
        this.cache = new CachedResult(overlay, region, downsample, paramHash, matching, valid);

        // Notify listener and trigger repaint on FX/AWT thread
        StatsListener listener = this.statsListener;
        final int fm = matching;
        final int fv = valid;
        Platform.runLater(() -> {
            if (listener != null) {
                listener.onStatsUpdated(fm, fv);
            }
            viewer.repaint();
        });
    }

    private long computeParamHash() {
        long h = 17;
        h = h * 31 + Float.floatToIntBits(angleLow);
        h = h * 31 + Float.floatToIntBits(angleHigh);
        h = h * 31 + Float.floatToIntBits(saturationThreshold);
        h = h * 31 + Float.floatToIntBits(valueThreshold);
        h = h * 31 + highlightRGB;
        return h;
    }

    /** Cleans up resources when the overlay is removed. */
    public void dispose() {
        computationId.incrementAndGet();
        active = false;
        cache = null;
        statsListener = null;
        computeExecutor.shutdownNow();
    }
}
