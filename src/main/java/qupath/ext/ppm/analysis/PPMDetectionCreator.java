package qupath.ext.ppm.analysis;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Creates QuPath detection objects from PPM angle-range thresholding using tiled processing.
 *
 * <p>Processes the image in tiles at a user-specified resolution level, computes a binary
 * mask of pixels matching the angle/saturation/value criteria, traces contours to create
 * geometries, and adds them as detection objects to the QuPath hierarchy.</p>
 *
 * <p>Tiled processing ensures this works for any image size, including whole-slide images.
 * Geometries at tile boundaries are merged via union so objects are not artificially split.</p>
 */
public class PPMDetectionCreator {

    private static final Logger logger = LoggerFactory.getLogger(PPMDetectionCreator.class);

    /** Tile size in pixels at the target downsample. */
    private static final int TILE_PIXELS = 2048;

    private PPMDetectionCreator() {}

    /**
     * Create detection objects from angle-range thresholding.
     *
     * @param server Image server for the PPM sum image
     * @param calibration PPM hue-to-angle calibration
     * @param angleLow Lower bound of angle range (degrees)
     * @param angleHigh Upper bound of angle range (degrees)
     * @param satThreshold Minimum HSB saturation for valid pixels
     * @param valThreshold Minimum HSB value (brightness) for valid pixels
     * @param downsample Resolution level for processing (1=full, 2=half, etc.)
     * @param parentROI If non-null, restrict detections to this ROI
     * @param minAreaUm2 Minimum detection area in um^2 (filter small noise)
     * @param hierarchy QuPath hierarchy to add detections to
     * @param pixelSizeUm Pixel size in microns (for area calculation)
     * @return Number of detections created
     */
    public static int create(
            ImageServer<BufferedImage> server,
            PPMCalibration calibration,
            float angleLow,
            float angleHigh,
            float satThreshold,
            float valThreshold,
            double downsample,
            ROI parentROI,
            double minAreaUm2,
            PathObjectHierarchy hierarchy,
            double pixelSizeUm) {

        // Determine processing bounds (from parent ROI or full image)
        int x0, y0, totalW, totalH;
        if (parentROI != null) {
            x0 = (int) parentROI.getBoundsX();
            y0 = (int) parentROI.getBoundsY();
            totalW = (int) Math.ceil(parentROI.getBoundsWidth());
            totalH = (int) Math.ceil(parentROI.getBoundsHeight());
        } else {
            x0 = 0;
            y0 = 0;
            totalW = server.getWidth();
            totalH = server.getHeight();
        }

        // Tile size in image coordinates
        int tileSizeImg = (int) (TILE_PIXELS * downsample);

        logger.info(
                "Creating PPM detections: region=({},{} {}x{}), downsample={}, " + "tileSize={}, angles=[{}, {}]",
                x0,
                y0,
                totalW,
                totalH,
                downsample,
                tileSizeImg,
                angleLow,
                angleHigh);

        // Process tiles and collect geometries
        List<Geometry> tileGeometries = new ArrayList<>();
        int tileCount = 0;

        for (int ty = y0; ty < y0 + totalH; ty += tileSizeImg) {
            for (int tx = x0; tx < x0 + totalW; tx += tileSizeImg) {
                int tw = Math.min(tileSizeImg, x0 + totalW - tx);
                int th = Math.min(tileSizeImg, y0 + totalH - ty);

                try {
                    Geometry geom = processTile(
                            server,
                            calibration,
                            tx,
                            ty,
                            tw,
                            th,
                            downsample,
                            angleLow,
                            angleHigh,
                            satThreshold,
                            valThreshold);
                    if (geom != null && !geom.isEmpty()) {
                        tileGeometries.add(geom);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process tile ({}, {}): {}", tx, ty, e.getMessage());
                }
                tileCount++;
            }
        }

        logger.info("Processed {} tiles, {} produced geometries", tileCount, tileGeometries.size());

        if (tileGeometries.isEmpty()) {
            logger.info("No matching regions found");
            return 0;
        }

        // Union all tile geometries to merge objects at tile boundaries
        Geometry union;
        try {
            GeometryFactory gf = new GeometryFactory();
            GeometryCollection gc = gf.createGeometryCollection(tileGeometries.toArray(new Geometry[0]));
            union = gc.union();
        } catch (Exception e) {
            logger.error("Failed to union tile geometries", e);
            return 0;
        }

        // Clip to parent ROI if present
        if (parentROI != null) {
            try {
                Geometry parentGeom = parentROI.getGeometry();
                union = union.intersection(parentGeom);
            } catch (Exception e) {
                logger.warn("Failed to clip to parent ROI: {}", e.getMessage());
            }
        }

        // Create detection objects from individual polygons
        String className = String.format("PPM %.0f-%.0f", angleLow, angleHigh);
        PathClass pathClass = PathClass.fromString(className);

        // Minimum area in pixel units at full resolution
        double pixelArea = pixelSizeUm * pixelSizeUm;
        double minAreaPx2 = pixelArea > 0 ? minAreaUm2 / pixelArea : 0;

        List<PathObject> detections = new ArrayList<>();
        for (int i = 0; i < union.getNumGeometries(); i++) {
            Geometry part = union.getGeometryN(i);
            if (part.getArea() < minAreaPx2) continue;

            try {
                ROI roi = GeometryTools.geometryToROI(part, ImagePlane.getDefaultPlane());
                PathObject detection = PathObjects.createDetectionObject(roi, pathClass);

                // Add area measurement in um^2
                double areaUm2 = part.getArea() * pixelArea;
                detection.getMeasurements().put("Area um^2", areaUm2);

                detections.add(detection);
            } catch (Exception e) {
                logger.debug("Failed to create detection from geometry: {}", e.getMessage());
            }
        }

        if (!detections.isEmpty()) {
            hierarchy.addObjects(detections);
            logger.info("Created {} PPM detections (class: {})", detections.size(), className);
        }

        return detections.size();
    }

    /**
     * Process a single tile: read image, compute binary mask, trace contours.
     *
     * @return Geometry in full-resolution image coordinates, or null if no matches
     */
    private static Geometry processTile(
            ImageServer<BufferedImage> server,
            PPMCalibration calibration,
            int x,
            int y,
            int w,
            int h,
            double downsample,
            float angleLow,
            float angleHigh,
            float satThreshold,
            float valThreshold)
            throws Exception {

        RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h);
        BufferedImage img = server.readRegion(request);
        int pw = img.getWidth();
        int ph = img.getHeight();

        // Create binary mask: 255 where matching, 0 elsewhere
        BufferedImage maskImg = new BufferedImage(pw, ph, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster maskRaster = maskImg.getRaster();
        float[] hsb = new float[3];
        boolean hasMatch = false;

        for (int py = 0; py < ph; py++) {
            for (int px = 0; px < pw; px++) {
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                Color.RGBtoHSB(r, g, b, hsb);

                if (hsb[1] >= satThreshold && hsb[2] >= valThreshold) {
                    float angle = (float) calibration.hueToAngle(hsb[0]);

                    boolean inRange;
                    if (angleLow <= angleHigh) {
                        inRange = angle >= angleLow && angle <= angleHigh;
                    } else {
                        inRange = angle >= angleLow || angle <= angleHigh;
                    }

                    if (inRange) {
                        maskRaster.setSample(px, py, 0, 255);
                        hasMatch = true;
                    }
                }
            }
        }

        if (!hasMatch) return null;

        // Trace contours from the binary mask
        // The RegionRequest provides coordinate offset and scale for the geometry
        return ContourTracing.createTracedGeometry(maskRaster, 127.5, 255.5, 0, request);
    }
}
