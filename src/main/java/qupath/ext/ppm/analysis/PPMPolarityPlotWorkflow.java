package qupath.ext.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.PPMPreferences;
import qupath.ext.ppm.service.ApposePPMService;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager.PPMAnalysisSet;
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
 * Workflow for computing and displaying a PPM polarity plot (rose diagram)
 * for a selected annotation.
 *
 * <p>All angle computation, histograms, and circular statistics are performed
 * by ppm_library (Python) via Appose. Java handles QuPath I/O
 * (reading image regions, extracting annotation shapes) and displays results.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMPolarityPlotWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMPolarityPlotWorkflow.class);

    private static Stage plotWindow;
    private static PolarHistogramPanel plotPanel;

    private static double getBirefThreshold() {
        return PPMPreferences.getBirefringenceThreshold();
    }

    private static int getHistogramBins() {
        return PPMPreferences.getHistogramBins();
    }

    private PPMPolarityPlotWorkflow() {}

    /**
     * Main entry point. Shows the polarity plot for the currently selected annotation.
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

        PathObject selected = imageData.getHierarchy().getSelectionModel().getSelectedObject();
        if (selected == null || !selected.isAnnotation()) {
            Dialogs.showErrorMessage(
                    "PPM Polarity Plot",
                    DocumentationHelper.withDocLink("Please select an annotation first.", "ppmPolarityPlot"));
            return;
        }

        ROI roi = selected.getROI();
        if (roi == null) {
            Dialogs.showErrorMessage(
                    "PPM Polarity Plot",
                    DocumentationHelper.withDocLink("Selected annotation has no ROI.", "ppmPolarityPlot"));
            return;
        }

        // Validate the current image is a PPM color (angle) image, not biref
        Project<BufferedImage> project = gui.getProject();
        ProjectImageEntry<BufferedImage> currentEntry = project != null ? project.getEntry(imageData) : null;

        if (currentEntry != null) {
            String angle = currentEntry.getMetadata().get("angle");
            String imageName = currentEntry.getImageName();
            boolean isBiref = (angle != null && angle.toLowerCase().contains("biref"))
                    || (imageName != null && imageName.toLowerCase().contains("biref"));

            if (isBiref) {
                PPMAnalysisSet analysisSetCheck =
                        project != null ? ImageMetadataManager.findPPMAnalysisSet(currentEntry, project) : null;
                String angleHint = "";
                if (analysisSetCheck != null && !analysisSetCheck.angleImages.isEmpty()) {
                    angleHint = "\n\nAngle images in this set:\n";
                    for (var angleImg : analysisSetCheck.angleImages) {
                        angleHint += "  - " + angleImg.getImageName() + "\n";
                    }
                }

                Dialogs.showErrorMessage(
                        "PPM Polarity Plot",
                        DocumentationHelper.withDocLink(
                                "This analysis requires a PPM color (angle) image.\n"
                                        + "The currently open image ("
                                        + (imageName != null ? imageName : "unknown")
                                        + ") is the birefringence image, which is grayscale.\n\n"
                                        + "Open any angle image from this set (e.g. positive or\n"
                                        + "negative angle), select an annotation, then run this\n"
                                        + "analysis again."
                                        + angleHint,
                                "ppmPolarityPlot"));
                return;
            }
        }

        PPMAnalysisSet analysisSet = null;
        if (currentEntry != null && project != null) {
            analysisSet = ImageMetadataManager.findPPMAnalysisSet(currentEntry, project);
        }

        String calibrationPath = null;
        if (analysisSet != null && analysisSet.hasCalibration()) {
            calibrationPath = analysisSet.calibrationPath;
        }
        if (calibrationPath == null && currentEntry != null) {
            calibrationPath = ImageMetadataManager.getPPMCalibration(currentEntry);
        }
        if (calibrationPath == null) {
            String activePath = qupath.ext.ppm.PPMPreferences.getActiveCalibrationPath();
            if (activePath != null && !activePath.isEmpty()) {
                calibrationPath = activePath;
            }
        }
        if (calibrationPath == null) {
            Dialogs.showErrorMessage(
                    "PPM Polarity Plot",
                    DocumentationHelper.withDocLink(
                            "No PPM calibration found. Run sunburst calibration first.", "ppmPolarityPlot"));
            return;
        }

        ensurePlotWindow(gui);

        final String finalCalibrationPath = calibrationPath;
        final ImageServer<BufferedImage> sumServer = imageData.getServer();
        final String annotationName = selected.getDisplayedName();
        final PPMAnalysisSet finalAnalysisSet = analysisSet;

        CompletableFuture.runAsync(() -> {
            try {
                computeAndDisplay(sumServer, roi, finalCalibrationPath, finalAnalysisSet, annotationName);
            } catch (Exception e) {
                logger.error("Polarity plot computation failed", e);
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "PPM Polarity Plot",
                        DocumentationHelper.withDocLink("Computation failed: " + e.getMessage(), "ppmPolarityPlot")));
            }
        });
    }

    private static void computeAndDisplay(
            ImageServer<BufferedImage> sumServer,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            String annotationName)
            throws Exception {

        // Ensure Appose PPM environment is initialized
        ApposePPMService service = ApposePPMService.getInstance();
        if (!service.isAvailable()) {
            logger.info("PPM analysis environment not ready, initializing...");
            service.initialize(msg -> logger.info("PPM setup: {}", msg));
        }

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        logger.info("Computing polarity plot for '{}': region {}x{} at ({},{})", annotationName, w, h, x, y);

        RegionRequest request = RegionRequest.createInstance(sumServer.getPath(), 1.0, x, y, w, h);

        NDArray sumNDArray = null;
        NDArray birefNDArray = null;
        NDArray roiNDArray = null;

        try {
            BufferedImage sumRegion = sumServer.readRegion(request);
            sumNDArray = PPMPerpendicularityWorkflow.bufferedImageToRGBNDArray(sumRegion);

            // Read biref region if available
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
                    logger.info("Read biref region for polarity analysis");
                } catch (Exception e) {
                    logger.warn("Could not read biref sibling: {}", e.getMessage());
                }
            }

            // Create ROI mask as NDArray
            roiNDArray = createROIMaskNDArray(roi, x, y, w, h);

            // Build Appose task inputs
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("sum_image", sumNDArray);
            inputs.put("calibration_path", calibrationPath);
            inputs.put("bins", getHistogramBins());
            inputs.put("saturation_threshold", PPMPreferences.getSaturationThreshold());
            inputs.put("value_threshold", PPMPreferences.getValueThreshold());

            if (birefNDArray != null) {
                inputs.put("biref_image", birefNDArray);
                inputs.put("biref_threshold", getBirefThreshold());
            }

            if (roiNDArray != null) {
                inputs.put("roi_mask", roiNDArray);
            }

            logger.info("Running polarity analysis via Appose");

            Task task = service.runTask("run_polarity", inputs);
            String json = (String) task.outputs.get("result_json");

            Gson gson = new Gson();
            JsonObject result = gson.fromJson(json, JsonObject.class);

            if (result.has("error") && !result.get("error").isJsonNull()) {
                throw new RuntimeException(
                        "Python analysis error: " + result.get("error").getAsString());
            }

            displayResults(result, annotationName);

        } finally {
            if (sumNDArray != null) sumNDArray.close();
            if (birefNDArray != null) birefNDArray.close();
            if (roiNDArray != null) roiNDArray.close();
        }
    }

    /**
     * Creates a binary ROI mask as an NDArray (H, W) uint8.
     */
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

    private static void displayResults(JsonObject result, String annotationName) {
        var countsArray = result.getAsJsonArray("histogram_counts");
        int[] counts = new int[countsArray.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = countsArray.get(i).getAsInt();
        }

        double circularMean = getDoubleOrNaN(result, "circular_mean");
        double circularStd = getDoubleOrNaN(result, "circular_std");
        double resultantLength = getDoubleOrNaN(result, "resultant_length");
        int nPixels = result.has("n_pixels") ? result.get("n_pixels").getAsInt() : 0;

        logger.info(
                "Polarity plot for '{}': {} valid pixels, mean=%.1f deg, std=%.1f deg, R=%.3f"
                        .formatted(circularMean, circularStd, resultantLength),
                annotationName,
                nPixels);

        Platform.runLater(() -> {
            if (plotPanel != null) {
                plotPanel.update(counts, circularMean, circularStd, resultantLength, nPixels, annotationName);
            }
            if (plotWindow != null) {
                plotWindow.show();
                plotWindow.toFront();
            }
        });
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
            Scene scene = new Scene(wrapper, 400, 400);
            plotWindow = new Stage();
            plotWindow.setTitle("PPM Polarity Plot");
            plotWindow.setScene(scene);
            plotWindow.initOwner(gui.getStage());
            plotWindow.setAlwaysOnTop(false);
        }
    }
}
