package qupath.ext.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.PPMPreferences;
import qupath.ext.ppm.analysis.PPMImageSetDiscovery.PPMAnalysisSet;
import qupath.ext.ppm.service.ApposePPMService;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Workflow for computing and displaying a PPM polarity plot (rose diagram).
 *
 * <p>Operates on a user-chosen set of annotations or detections (filtered by class)
 * or on the current selection. Per-object polarity is computed via ppm_library
 * (Python) over Appose; circular statistics (mean angle, std, resultant length R,
 * valid pixel count, dominant bin) are written back to each object's measurement
 * list. The dialog then shows an aggregate polar histogram over all processed
 * objects with combined circular statistics.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMPolarityPlotWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMPolarityPlotWorkflow.class);

    // Measurement keys written to each object's MeasurementList. Keep these stable --
    // downstream PPM segmentation code reads these to filter detections by polarity.
    static final String MEAS_MEAN_ANGLE = "PPM Polarity: Mean angle (deg)";
    static final String MEAS_CIRC_STD = "PPM Polarity: Circ std (deg)";
    static final String MEAS_R = "PPM Polarity: Resultant length";
    static final String MEAS_N_PIXELS = "PPM Polarity: Valid pixels";
    static final String MEAS_DOMINANT_BIN = "PPM Polarity: Dominant bin center (deg)";

    private static Stage plotWindow;
    private static PolarHistogramPanel plotPanel;

    private static double getBirefThreshold() {
        return PPMPreferences.getBirefringenceThreshold();
    }

    private static int getHistogramBins() {
        return PPMPreferences.getHistogramBins();
    }

    private PPMPolarityPlotWorkflow() {}

    /** Per-object result returned from {@link #computeForObject}. */
    private static final class ObjectResult {
        final PathObject obj;
        final int[] counts;
        final double meanDeg;
        final double stdDeg;
        final double resultantLength;
        final int nPixels;

        ObjectResult(PathObject obj, int[] counts, double meanDeg, double stdDeg, double R, int nPixels) {
            this.obj = obj;
            this.counts = counts;
            this.meanDeg = meanDeg;
            this.stdDeg = stdDeg;
            this.resultantLength = R;
            this.nPixels = nPixels;
        }
    }

    /**
     * Main entry point. Opens the object-selection dialog, then runs the analysis
     * on the chosen objects.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run polarity plot workflow", e);
                Dialogs.showErrorMessage(
                        "PPM Polarity Plot",
                        DocumentationHelper.withDocLink("Error: " + e.getMessage(), "ppmPolarityPlot"));
            }
        });
    }

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage(
                    "PPM Polarity Plot",
                    DocumentationHelper.withDocLink("QuPath is not available.", "ppmPolarityPlot"));
            return;
        }

        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(
                    "PPM Polarity Plot", DocumentationHelper.withDocLink("No image is open.", "ppmPolarityPlot"));
            return;
        }

        Project<BufferedImage> project = gui.getProject();
        ProjectImageEntry<BufferedImage> currentEntry = project != null ? project.getEntry(imageData) : null;

        // Reject the biref grayscale -- polarity needs the color (angle) image.
        if (currentEntry != null) {
            String angle = currentEntry.getMetadata().get("angle");
            String imageName = currentEntry.getImageName();
            boolean isBiref = (angle != null && angle.toLowerCase().contains("biref"))
                    || (imageName != null && imageName.toLowerCase().contains("biref"));
            if (isBiref) {
                PPMAnalysisSet analysisSetCheck =
                        project != null ? PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project) : null;
                StringBuilder angleHint = new StringBuilder();
                if (analysisSetCheck != null && !analysisSetCheck.angleImages.isEmpty()) {
                    angleHint.append("\n\nAngle images in this set:\n");
                    for (var angleImg : analysisSetCheck.angleImages) {
                        angleHint.append("  - ").append(angleImg.getImageName()).append("\n");
                    }
                }
                Dialogs.showErrorMessage(
                        "PPM Polarity Plot",
                        DocumentationHelper.withDocLink(
                                "This analysis requires a PPM color (angle) image.\n"
                                        + "The currently open image ("
                                        + (imageName != null ? imageName : "unknown")
                                        + ") is the birefringence image, which is grayscale.\n\n"
                                        + "Open any angle image from this set, then run this analysis again."
                                        + angleHint,
                                "ppmPolarityPlot"));
                return;
            }
        }

        // Resolve calibration once -- shared across all objects in the run.
        PPMAnalysisSet analysisSet = (currentEntry != null && project != null)
                ? PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project)
                : null;
        String calibrationPath = resolveCalibrationPath(analysisSet, currentEntry);
        if (calibrationPath == null) {
            Dialogs.showErrorMessage(
                    "PPM Polarity Plot",
                    DocumentationHelper.withDocLink(
                            "No PPM calibration found. Run sunburst calibration first.", "ppmPolarityPlot"));
            return;
        }

        // Show the object-selection dialog. Returns the resolved object list.
        Optional<PolarityPlotConfigDialog.Result> chosen = PolarityPlotConfigDialog.show(gui.getStage(), imageData);
        if (chosen.isEmpty()) {
            logger.info("Polarity plot run cancelled");
            return;
        }

        PolarityPlotConfigDialog.Result picks = chosen.get();
        logger.info(
                "Polarity plot: running on {} object(s), type={}, classes={}",
                picks.resolvedObjects.size(),
                picks.type,
                picks.classNames);

        ensurePlotWindow(gui);
        Platform.runLater(() -> {
            if (plotPanel != null) {
                plotPanel.showRunning(picks.resolvedObjects.size());
            }
            plotWindow.show();
            plotWindow.toFront();
        });

        final String finalCalibrationPath = calibrationPath;
        final ImageServer<BufferedImage> sumServer = imageData.getServer();
        final PPMAnalysisSet finalAnalysisSet = analysisSet;
        final List<PathObject> targets = picks.resolvedObjects;
        final String typeLabel = labelForType(picks.type);
        final Set<String> classFilter = picks.classNames;

        CompletableFuture.runAsync(() -> {
            try {
                runMultiObject(sumServer, targets, finalCalibrationPath, finalAnalysisSet, typeLabel, classFilter);
            } catch (Exception e) {
                logger.error("Polarity plot computation failed", e);
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "PPM Polarity Plot",
                        DocumentationHelper.withDocLink("Computation failed: " + e.getMessage(), "ppmPolarityPlot")));
            }
        });
    }

    private static String resolveCalibrationPath(PPMAnalysisSet analysisSet, ProjectImageEntry<BufferedImage> entry) {
        if (analysisSet != null && analysisSet.hasCalibration()) {
            return analysisSet.calibrationPath;
        }
        if (entry != null) {
            String fromEntry = ImageMetadataManager.getPPMCalibration(entry);
            if (fromEntry != null) return fromEntry;
        }
        String activePath = qupath.ext.ppm.PPMPreferences.getActiveCalibrationPath();
        if (activePath != null && !activePath.isEmpty()) return activePath;
        return null;
    }

    private static String labelForType(PolarityPlotConfigDialog.ObjectType type) {
        return switch (type) {
            case CURRENT_SELECTION -> "selected objects";
            case ANNOTATIONS -> "annotation";
            case DETECTIONS -> "detection";
        };
    }

    /**
     * Iterates over the chosen objects, computes per-object polarity, writes
     * measurements back to each object, and aggregates a combined histogram +
     * combined circular statistics for the final display.
     */
    private static void runMultiObject(
            ImageServer<BufferedImage> sumServer,
            List<PathObject> targets,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            String typeLabel,
            Set<String> classFilter)
            throws Exception {

        ApposePPMService service = ApposePPMService.getInstance();
        if (!service.isAvailable()) {
            logger.info("PPM analysis environment not ready, initializing...");
            service.initialize(msg -> logger.info("PPM setup: {}", msg));
        }

        int bins = getHistogramBins();
        int[] aggregateCounts = new int[bins];
        long aggregateNPixels = 0;
        double vectorX = 0.0; // sum of N_i * R_i * cos(2*theta_i)
        double vectorY = 0.0; // sum of N_i * R_i * sin(2*theta_i)
        int processed = 0;
        int skipped = 0;
        Map<String, Integer> classBreakdown = new LinkedHashMap<>();

        for (int i = 0; i < targets.size(); i++) {
            PathObject obj = targets.get(i);
            updateRunningStatus(i + 1, targets.size());
            try {
                ObjectResult r = computeForObject(service, sumServer, analysisSet, calibrationPath, obj, bins);
                if (r == null) {
                    skipped++;
                    continue;
                }
                writeMeasurements(obj, r, bins);
                if (r.counts.length == aggregateCounts.length) {
                    for (int b = 0; b < bins; b++) aggregateCounts[b] += r.counts[b];
                }
                aggregateNPixels += r.nPixels;
                if (!Double.isNaN(r.meanDeg) && !Double.isNaN(r.resultantLength) && r.nPixels > 0) {
                    double doubled = Math.toRadians(r.meanDeg * 2.0);
                    double weight = r.nPixels * r.resultantLength;
                    vectorX += weight * Math.cos(doubled);
                    vectorY += weight * Math.sin(doubled);
                }
                processed++;
                String className = obj.getPathClass() != null
                        ? obj.getPathClass().toString()
                        : PolarityPlotConfigDialog.UNCLASSIFIED_LABEL;
                classBreakdown.merge(className, 1, Integer::sum);
            } catch (Exception e) {
                logger.warn(
                        "Polarity plot: object {} of {} failed ({}); continuing.",
                        i + 1,
                        targets.size(),
                        e.getMessage());
                skipped++;
            }
        }

        // Combine per-object stats into population-level mean, R, std for axial data.
        double aggregateMeanDeg;
        double aggregateR;
        double aggregateStdDeg;
        if (aggregateNPixels > 0 && (vectorX != 0.0 || vectorY != 0.0)) {
            double magnitude = Math.hypot(vectorX, vectorY);
            aggregateR = magnitude / aggregateNPixels;
            // Doubled-angle mean; halve and wrap to 0-180.
            double meanDoubled = Math.atan2(vectorY, vectorX); // radians in (-PI, PI]
            double meanDeg = Math.toDegrees(meanDoubled) / 2.0;
            if (meanDeg < 0) meanDeg += 180.0;
            aggregateMeanDeg = meanDeg;
            // Axial circular std from Mardia: convert to "doubled" std then halve.
            if (aggregateR > 0 && aggregateR <= 1) {
                aggregateStdDeg = Math.toDegrees(Math.sqrt(-2.0 * Math.log(aggregateR))) / 2.0;
            } else {
                aggregateStdDeg = Double.NaN;
            }
        } else {
            aggregateMeanDeg = Double.NaN;
            aggregateR = Double.NaN;
            aggregateStdDeg = Double.NaN;
        }

        final String title = buildTitle(processed, skipped, typeLabel, classFilter, classBreakdown);
        final int totalPixels = (int) Math.min(aggregateNPixels, Integer.MAX_VALUE);
        logger.info(
                "Polarity plot aggregate: {} processed, {} skipped, {} valid pixels, mean={}, std={}, R={}",
                processed,
                skipped,
                aggregateNPixels,
                aggregateMeanDeg,
                aggregateStdDeg,
                aggregateR);

        final double finalMean = aggregateMeanDeg;
        final double finalStd = aggregateStdDeg;
        final double finalR = aggregateR;
        Platform.runLater(() -> {
            if (plotPanel != null) {
                plotPanel.update(aggregateCounts, finalMean, finalStd, finalR, totalPixels, title);
            }
            if (plotWindow != null) {
                plotWindow.show();
                plotWindow.toFront();
            }
        });
    }

    private static void updateRunningStatus(int current, int total) {
        Platform.runLater(() -> {
            if (plotPanel != null) plotPanel.showProgress(current, total);
        });
    }

    private static String buildTitle(
            int processed, int skipped, String typeLabel, Set<String> classFilter, Map<String, Integer> breakdown) {
        StringBuilder sb = new StringBuilder();
        sb.append(processed).append(' ').append(typeLabel).append(processed == 1 ? "" : "s");
        if (!classFilter.isEmpty()) {
            // Order class names by count desc for the title summary.
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(breakdown.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            sb.append(" (");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries.get(i).getKey())
                        .append(": ")
                        .append(entries.get(i).getValue());
            }
            sb.append(")");
        }
        if (skipped > 0) sb.append("  -- ").append(skipped).append(" skipped");
        return sb.toString();
    }

    private static ObjectResult computeForObject(
            ApposePPMService service,
            ImageServer<BufferedImage> sumServer,
            PPMAnalysisSet analysisSet,
            String calibrationPath,
            PathObject obj,
            int bins)
            throws Exception {

        ROI roi = obj.getROI();
        if (roi == null) return null;

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());
        if (w <= 0 || h <= 0) return null;

        RegionRequest request = RegionRequest.createInstance(sumServer.getPath(), 1.0, x, y, w, h);

        NDArray sumNDArray = null;
        NDArray birefNDArray = null;
        NDArray roiNDArray = null;

        try {
            BufferedImage sumRegion = sumServer.readRegion(request);
            sumNDArray = PPMPerpendicularityWorkflow.bufferedImageToRGBNDArray(sumRegion);

            if (analysisSet != null && analysisSet.hasBirefImage()) {
                try {
                    @SuppressWarnings("unchecked")
                    ImageData<BufferedImage> birefData =
                            (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
                    ImageServer<BufferedImage> birefServer = birefData.getServer();
                    RegionRequest birefRequest = RegionRequest.createInstance(birefServer.getPath(), 1.0, x, y, w, h);
                    BufferedImage birefRegion = birefServer.readRegion(birefRequest);
                    birefNDArray = PPMPerpendicularityWorkflow.bufferedImageToGray16NDArray(birefRegion);
                    birefServer.close();
                } catch (Exception e) {
                    logger.warn("Could not read biref sibling: {}", e.getMessage());
                }
            }

            roiNDArray = createROIMaskNDArray(roi, x, y, w, h);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("sum_image", sumNDArray);
            inputs.put("calibration_path", calibrationPath);
            inputs.put("bins", bins);
            inputs.put("saturation_threshold", PPMPreferences.getSaturationThreshold());
            inputs.put("value_threshold", PPMPreferences.getValueThreshold());

            if (birefNDArray != null) {
                inputs.put("biref_image", birefNDArray);
                inputs.put("biref_threshold", getBirefThreshold());
            }
            if (roiNDArray != null) {
                inputs.put("roi_mask", roiNDArray);
            }

            Task task = service.runTask("run_polarity", inputs);
            String json = (String) task.outputs.get("result_json");

            Gson gson = new Gson();
            JsonObject result = gson.fromJson(json, JsonObject.class);

            if (result.has("error") && !result.get("error").isJsonNull()) {
                throw new RuntimeException(
                        "Python analysis error: " + result.get("error").getAsString());
            }

            var countsArray = result.getAsJsonArray("histogram_counts");
            int[] counts = new int[countsArray.size()];
            for (int i = 0; i < counts.length; i++)
                counts[i] = countsArray.get(i).getAsInt();

            double mean = getDoubleOrNaN(result, "circular_mean");
            double std = getDoubleOrNaN(result, "circular_std");
            double R = getDoubleOrNaN(result, "resultant_length");
            int nPixels = result.has("n_pixels") ? result.get("n_pixels").getAsInt() : 0;

            return new ObjectResult(obj, counts, mean, std, R, nPixels);
        } finally {
            if (sumNDArray != null) sumNDArray.close();
            if (birefNDArray != null) birefNDArray.close();
            if (roiNDArray != null) roiNDArray.close();
        }
    }

    private static void writeMeasurements(PathObject obj, ObjectResult r, int bins) {
        var ml = obj.getMeasurementList();
        if (!Double.isNaN(r.meanDeg)) ml.put(MEAS_MEAN_ANGLE, r.meanDeg);
        if (!Double.isNaN(r.stdDeg)) ml.put(MEAS_CIRC_STD, r.stdDeg);
        if (!Double.isNaN(r.resultantLength)) ml.put(MEAS_R, r.resultantLength);
        ml.put(MEAS_N_PIXELS, r.nPixels);
        // Dominant bin center = where the histogram peak lies.
        if (r.counts != null && r.counts.length == bins && bins > 0) {
            int maxBin = 0;
            int maxCount = -1;
            for (int i = 0; i < bins; i++) {
                if (r.counts[i] > maxCount) {
                    maxCount = r.counts[i];
                    maxBin = i;
                }
            }
            double binWidthDeg = 180.0 / bins;
            ml.put(MEAS_DOMINANT_BIN, (maxBin + 0.5) * binWidthDeg);
        }
    }

    /** Creates a binary ROI mask as an NDArray (H, W) uint8. */
    static NDArray createROIMaskNDArray(ROI roi, int offsetX, int offsetY, int width, int height) {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (roi.contains(offsetX + x + 0.5, offsetY + y + 0.5)) {
                    mask.getRaster().setSample(x, y, 0, 255);
                }
            }
        }
        return PPMPerpendicularityWorkflow.bufferedImageToGrayNDArray(mask);
    }

    private static double getDoubleOrNaN(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return Double.NaN;
        }
        return obj.get(key).getAsDouble();
    }

    private static void ensurePlotWindow(QuPathGUI gui) {
        if (plotWindow == null || !plotWindow.isShowing()) {
            plotPanel = new PolarHistogramPanel();
            javafx.scene.layout.VBox wrapper = new javafx.scene.layout.VBox();
            javafx.scene.control.Button docButton = DocumentationHelper.createHelpButton("ppmPolarityPlot");
            if (docButton != null) {
                javafx.scene.layout.HBox helpBar = new javafx.scene.layout.HBox(docButton);
                helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
                helpBar.setPadding(new javafx.geometry.Insets(4, 8, 0, 0));
                wrapper.getChildren().add(helpBar);
            }
            javafx.scene.layout.VBox.setVgrow(plotPanel, javafx.scene.layout.Priority.ALWAYS);
            wrapper.getChildren().add(plotPanel);
            Scene scene = new Scene(wrapper, 460, 520);
            plotWindow = new Stage();
            plotWindow.setTitle("PPM Polarity Plot");
            plotWindow.setScene(scene);
            plotWindow.initOwner(gui.getStage());
            plotWindow.setAlwaysOnTop(false);
        }
    }
}
