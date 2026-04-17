package qupath.ext.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
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
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Batch PPM analysis workflow: discovers PPM analysis sets across a project,
 * presents a selection UI, and runs polarity and/or perpendicularity analysis
 * on all annotations, storing results as measurements and exporting CSV.
 *
 * <p>Analysis is performed via Appose (ppm_library), using a persistent Python
 * process for fast subsequent calls without per-annotation subprocess overhead.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMBatchAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMBatchAnalysisWorkflow.class);

    private static double getBirefThreshold() {
        return PPMPreferences.getBirefringenceThreshold();
    }

    private static int getHistogramBins() {
        return PPMPreferences.getHistogramBins();
    }

    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    private PPMBatchAnalysisWorkflow() {}

    /**
     * Main entry point.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run batch analysis workflow", e);
                Dialogs.showErrorMessage(
                        "Batch PPM Analysis",
                        DocumentationHelper.withDocLink("Error: " + e.getMessage(), "ppmBatchAnalysis"));
            }
        });
    }

    // ========================================================================
    // Discovery
    // ========================================================================

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage(
                    "Batch PPM Analysis",
                    DocumentationHelper.withDocLink("QuPath is not available.", "ppmBatchAnalysis"));
            return;
        }

        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Batch PPM Analysis",
                    DocumentationHelper.withDocLink(
                            "A QuPath project is required. Create or open a project first.", "ppmBatchAnalysis"));
            return;
        }

        List<PPMBatchAnalysisPanel.AnalysisSetItem> discoveredSets = discoverAnalysisSets(project);

        if (discoveredSets.isEmpty()) {
            Dialogs.showErrorMessage(
                    "Batch PPM Analysis",
                    DocumentationHelper.withDocLink(
                            "No qualified PPM analysis sets found in this project.\n\n"
                                    + "Requirements: PPM modality images with at least one angle image\n"
                                    + "and a calibration file.",
                            "ppmBatchAnalysis"));
            return;
        }

        List<String> allClasses = collectAnnotationClasses(project, discoveredSets);

        showConfigPanel(gui, project, discoveredSets, allClasses);
    }

    @SuppressWarnings("unchecked")
    private static List<PPMBatchAnalysisPanel.AnalysisSetItem> discoverAnalysisSets(Project<BufferedImage> project) {

        Map<String, List<ProjectImageEntry<BufferedImage>>> groups = new LinkedHashMap<>();

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String modality = entry.getMetadata().get(ImageMetadataManager.MODALITY);
            if (modality == null || !modality.toLowerCase().startsWith("ppm")) continue;

            int collection = ImageMetadataManager.getImageCollection(entry);
            if (collection < 0) continue;

            String sample = ImageMetadataManager.getSampleName(entry);
            String annotation = entry.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);

            String key = collection + "|" + Objects.toString(sample, "") + "|" + Objects.toString(annotation, "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }

        List<PPMBatchAnalysisPanel.AnalysisSetItem> items = new ArrayList<>();

        for (Map.Entry<String, List<ProjectImageEntry<BufferedImage>>> groupEntry : groups.entrySet()) {
            List<ProjectImageEntry<BufferedImage>> members = groupEntry.getValue();

            // Find any angle image (non-biref) to use as the RGB color source
            ProjectImageEntry<BufferedImage> colorImage = null;
            boolean hasBiref = false;
            String calibrationPath = null;

            for (ProjectImageEntry<BufferedImage> member : members) {
                String angle = member.getMetadata().get(ImageMetadataManager.ANGLE);
                String name = member.getImageName().toLowerCase();

                if (isBirefImage(angle, name)) {
                    hasBiref = true;
                } else if (colorImage == null) {
                    // First non-biref angle image becomes the color source
                    colorImage = member;
                }

                String cal = member.getMetadata().get(ImageMetadataManager.PPM_CALIBRATION);
                if (cal != null && !cal.isEmpty() && calibrationPath == null) {
                    calibrationPath = cal;
                }
            }

            if (calibrationPath == null) {
                String activeCal = PPMPreferences.getActiveCalibrationPath();
                if (activeCal != null && !activeCal.isEmpty()) {
                    calibrationPath = activeCal;
                }
            }

            if (colorImage == null || calibrationPath == null) {
                logger.debug(
                        "Skipping group {} - colorImage={}, cal={}",
                        groupEntry.getKey(),
                        colorImage != null ? "yes" : "no",
                        calibrationPath != null ? "yes" : "no");
                continue;
            }

            int annotationCount = 0;
            try {
                ImageData<BufferedImage> imgData = (ImageData<BufferedImage>) colorImage.readImageData();
                annotationCount = imgData.getHierarchy().getAnnotationObjects().size();
            } catch (Exception e) {
                logger.debug("Could not read annotations for {}: {}", colorImage.getImageName(), e.getMessage());
            }

            int collection = ImageMetadataManager.getImageCollection(colorImage);
            String sample = ImageMetadataManager.getSampleName(colorImage);
            String annotation = colorImage.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);

            String display = String.format(
                    "[%d] %s%s%s (%d annotations%s)",
                    collection,
                    colorImage.getImageName(),
                    sample != null ? " | " + sample : "",
                    annotation != null ? " | " + annotation : "",
                    annotationCount,
                    hasBiref ? ", +biref" : "");

            items.add(new PPMBatchAnalysisPanel.AnalysisSetItem(
                    display,
                    colorImage.getImageName(),
                    collection,
                    sample,
                    annotation,
                    calibrationPath,
                    hasBiref,
                    annotationCount));
        }

        logger.info("Discovered {} qualified PPM analysis sets", items.size());
        return items;
    }

    @SuppressWarnings("unchecked")
    private static List<String> collectAnnotationClasses(
            Project<BufferedImage> project, List<PPMBatchAnalysisPanel.AnalysisSetItem> sets) {

        Set<String> classes = new LinkedHashSet<>();

        for (PPMBatchAnalysisPanel.AnalysisSetItem item : sets) {
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (entry.getImageName().equals(item.imageName)) {
                    try {
                        ImageData<BufferedImage> imgData = (ImageData<BufferedImage>) entry.readImageData();
                        for (PathObject ann : imgData.getHierarchy().getAnnotationObjects()) {
                            PathClass pc = ann.getPathClass();
                            if (pc != null) {
                                classes.add(pc.toString());
                            }
                        }
                    } catch (Exception e) {
                        // skip
                    }
                    break;
                }
            }
        }

        return new ArrayList<>(classes);
    }

    // ========================================================================
    // Config UI
    // ========================================================================

    private static void showConfigPanel(
            QuPathGUI gui,
            Project<BufferedImage> project,
            List<PPMBatchAnalysisPanel.AnalysisSetItem> discoveredSets,
            List<String> allClasses) {

        Stage dialog = new Stage();
        dialog.setTitle("Batch PPM Analysis");
        dialog.initOwner(gui.getStage());

        javafx.scene.control.Button helpButton = DocumentationHelper.createHelpButton("ppmBatchAnalysis");
        PPMBatchAnalysisPanel panel = new PPMBatchAnalysisPanel(discoveredSets, allClasses);

        panel.setOnCancel(dialog::close);
        panel.setOnRun(() -> {
            List<PPMBatchAnalysisPanel.AnalysisSetItem> selected = panel.getSelectedItems();
            if (selected.isEmpty()) {
                Dialogs.showErrorMessage(
                        "Batch PPM Analysis",
                        DocumentationHelper.withDocLink("No analysis sets selected.", "ppmBatchAnalysis"));
                return;
            }
            if (!panel.isPolaritySelected() && !panel.isPerpendicularitySelected()) {
                Dialogs.showErrorMessage(
                        "Batch PPM Analysis",
                        DocumentationHelper.withDocLink("Select at least one analysis type.", "ppmBatchAnalysis"));
                return;
            }
            if (panel.isPerpendicularitySelected()
                    && (panel.getBoundaryClass() == null
                            || panel.getBoundaryClass().isEmpty())) {
                Dialogs.showErrorMessage(
                        "Batch PPM Analysis",
                        DocumentationHelper.withDocLink(
                                "Select a boundary annotation class for perpendicularity analysis.",
                                "ppmBatchAnalysis"));
                return;
            }

            // Persist user-modified values for next session
            if (panel.isPerpendicularitySelected()) {
                PPMPreferences.setDilationUm(panel.getDilationUm());
                PPMPreferences.setTacsThresholdDeg(panel.getTacsThreshold());
            }

            dialog.close();

            cancelled.set(false);
            runBatchAnalysis(
                    gui,
                    project,
                    selected,
                    panel.isPolaritySelected(),
                    panel.isPerpendicularitySelected(),
                    panel.getBoundaryClass(),
                    panel.getDilationUm(),
                    panel.getZoneMode(),
                    panel.getTacsThreshold(),
                    panel.getFillHoles());
        });

        VBox dialogRoot = new VBox(panel);
        if (helpButton != null) {
            javafx.scene.layout.HBox helpBar = new javafx.scene.layout.HBox(helpButton);
            helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            helpBar.setPadding(new Insets(5, 10, 0, 10));
            dialogRoot.getChildren().add(0, helpBar);
        }
        javafx.scene.layout.VBox.setVgrow(panel, javafx.scene.layout.Priority.ALWAYS);
        dialog.setScene(new Scene(dialogRoot, 600, 700));
        dialog.show();
    }

    // ========================================================================
    // Batch execution
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void runBatchAnalysis(
            QuPathGUI gui,
            Project<BufferedImage> project,
            List<PPMBatchAnalysisPanel.AnalysisSetItem> selectedSets,
            boolean runPolarity,
            boolean runPerpendicularity,
            String boundaryClass,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles) {

        Stage progressStage = new Stage();
        progressStage.setTitle("Batch PPM Analysis - Running");
        progressStage.initOwner(gui.getStage());

        javafx.scene.control.Label progressLabel = new javafx.scene.control.Label("Starting...");
        progressLabel.setWrapText(true);
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(500);
        javafx.scene.control.Button cancelBtn = new javafx.scene.control.Button("Cancel");
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            cancelBtn.setDisable(true);
            cancelBtn.setText("Cancelling...");
        });

        VBox progressBox = new VBox(10, progressLabel, progressBar, cancelBtn);
        progressBox.setPadding(new Insets(15));
        progressStage.setScene(new Scene(progressBox, 550, 120));
        progressStage.show();

        Path projectDir = project.getPath().getParent();
        Path outputDir = projectDir.resolve("analysis").resolve("batch_ppm");

        CompletableFuture.runAsync(() -> {
            PPMBatchResultsWriter writer = new PPMBatchResultsWriter();
            int totalSets = selectedSets.size();
            int processedAnnotations = 0;
            int errors = 0;

            try {
                // Ensure Appose PPM environment is initialized before batch
                ApposePPMService service = ApposePPMService.getInstance();
                if (!service.isAvailable()) {
                    updateProgress(progressLabel, progressBar, "Initializing PPM analysis environment...", -1);
                    service.initialize(msg -> updateProgress(progressLabel, progressBar, msg, -1));
                }
            } catch (IOException e) {
                logger.error("Failed to initialize PPM environment: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage(
                            "Batch PPM Analysis",
                            DocumentationHelper.withDocLink(
                                    "Failed to initialize PPM analysis environment: " + e.getMessage(),
                                    "ppmBatchAnalysis"));
                });
                return;
            }

            for (int setIdx = 0; setIdx < totalSets; setIdx++) {
                if (cancelled.get()) break;

                PPMBatchAnalysisPanel.AnalysisSetItem item = selectedSets.get(setIdx);
                final int setNum = setIdx + 1;

                updateProgress(
                        progressLabel,
                        progressBar,
                        String.format("Set %d/%d: %s", setNum, totalSets, item.imageName),
                        (double) setIdx / totalSets);

                ProjectImageEntry<BufferedImage> colorEntry = null;
                for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                    if (entry.getImageName().equals(item.imageName)) {
                        colorEntry = entry;
                        break;
                    }
                }
                if (colorEntry == null) {
                    logger.warn("Could not find color image entry: {}", item.imageName);
                    errors++;
                    continue;
                }

                try {
                    ImageData<BufferedImage> imageData = (ImageData<BufferedImage>) colorEntry.readImageData();
                    ImageServer<BufferedImage> colorServer = imageData.getServer();

                    PixelCalibration pixelCal = colorServer.getPixelCalibration();
                    double pixelSizeUm = pixelCal.hasPixelSizeMicrons() ? pixelCal.getAveragedPixelSizeMicrons() : 1.0;

                    PPMAnalysisSet analysisSet = PPMImageSetDiscovery.findPPMAnalysisSet(colorEntry, project);

                    Collection<PathObject> annotations =
                            imageData.getHierarchy().getAnnotationObjects();

                    if (annotations.isEmpty()) {
                        logger.info("No annotations on {}, skipping", item.imageName);
                        continue;
                    }

                    String collectionStr = String.valueOf(item.imageCollection);

                    // Pass 1: Polarity analysis on ALL annotations
                    if (runPolarity) {
                        int annIdx = 0;
                        int totalAnn = annotations.size();
                        for (PathObject annotation : annotations) {
                            if (cancelled.get()) break;

                            annIdx++;
                            String annName = annotation.getDisplayedName();
                            String annClass = annotation.getPathClass() != null
                                    ? annotation.getPathClass().toString()
                                    : "";

                            updateProgress(
                                    progressLabel,
                                    progressBar,
                                    String.format(
                                            "Set %d/%d: %s - polarity %d/%d: %s",
                                            setNum, totalSets, item.imageName, annIdx, totalAnn, annName),
                                    ((double) setIdx + (double) annIdx / totalAnn * 0.5) / totalSets);

                            ROI roi = annotation.getROI();
                            if (roi == null) continue;

                            try {
                                JsonObject polarityResult =
                                        runPolarityForAnnotation(colorServer, roi, item.calibrationPath, analysisSet);

                                writer.storePolarityMeasurements(annotation, polarityResult);
                                writer.addPolarityRow(
                                        item.imageName,
                                        collectionStr,
                                        item.sampleName,
                                        annName,
                                        annClass,
                                        polarityResult);

                                processedAnnotations++;
                            } catch (Exception e) {
                                logger.warn("Polarity failed for {} / {}: {}", item.imageName, annName, e.getMessage());
                                errors++;
                            }
                        }
                    }

                    // Pass 2: Perpendicularity on boundary-class annotations only
                    if (runPerpendicularity && boundaryClass != null) {
                        List<PathObject> boundaryAnnotations = annotations.stream()
                                .filter(a -> a.getPathClass() != null
                                        && a.getPathClass().toString().equals(boundaryClass))
                                .collect(Collectors.toList());

                        int bIdx = 0;
                        int totalB = boundaryAnnotations.size();
                        for (PathObject boundary : boundaryAnnotations) {
                            if (cancelled.get()) break;

                            bIdx++;
                            updateProgress(
                                    progressLabel,
                                    progressBar,
                                    String.format(
                                            "Set %d/%d: %s - perpendicularity %d/%d: %s",
                                            setNum,
                                            totalSets,
                                            item.imageName,
                                            bIdx,
                                            totalB,
                                            boundary.getDisplayedName()),
                                    ((double) setIdx + 0.5 + (double) bIdx / totalB * 0.5) / totalSets);

                            try {
                                JsonObject perpResult = runPerpendicularityForAnnotation(
                                        colorServer,
                                        boundary.getROI(),
                                        item.calibrationPath,
                                        analysisSet,
                                        pixelSizeUm,
                                        dilationUm,
                                        zoneMode,
                                        tacsThreshold,
                                        fillHoles);

                                writer.storePerpendicularityMeasurements(boundary, perpResult);
                                writer.addPerpendicularityRow(
                                        item.imageName,
                                        collectionStr,
                                        item.sampleName,
                                        boundary.getDisplayedName(),
                                        boundaryClass,
                                        perpResult);

                                processedAnnotations++;
                            } catch (Exception e) {
                                logger.warn(
                                        "Perpendicularity failed for {} / {}: {}",
                                        item.imageName,
                                        boundary.getDisplayedName(),
                                        e.getMessage());
                                errors++;
                            }
                        }
                    }

                    colorEntry.saveImageData(imageData);

                } catch (Exception e) {
                    logger.error("Failed to process set {}: {}", item.imageName, e.getMessage(), e);
                    errors++;
                }
            }

            // Write CSV
            final int finalProcessed = processedAnnotations;
            final int finalErrors = errors;
            try {
                Path csvPath = outputDir.resolve("batch_results.csv");
                int rows = writer.writeCSV(csvPath);

                Platform.runLater(() -> {
                    progressStage.close();
                    String msg = String.format(
                            "Batch analysis complete.\n\n"
                                    + "Annotations processed: %d\n"
                                    + "CSV rows written: %d\n"
                                    + "Errors: %d\n\n"
                                    + "Results saved to:\n%s\n\n"
                                    + "Measurements also stored on annotations\n"
                                    + "(visible in QuPath measurement table).",
                            finalProcessed, rows, finalErrors, csvPath.toString());

                    if (cancelled.get()) {
                        msg = "Analysis cancelled.\n\n" + msg;
                    }

                    Dialogs.showMessageDialog("Batch PPM Analysis", msg);
                });

            } catch (Exception e) {
                logger.error("Failed to write CSV: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage(
                            "Batch PPM Analysis",
                            DocumentationHelper.withDocLink(
                                    "Analysis completed but CSV write failed: " + e.getMessage()
                                            + "\n\nMeasurements were still stored on annotations.",
                                    "ppmBatchAnalysis"));
                });
            }

            logger.info("Batch PPM analysis complete: {} processed, {} errors", finalProcessed, finalErrors);
        });
    }

    // ========================================================================
    // Per-annotation analysis via Appose
    // ========================================================================

    private static JsonObject runPolarityForAnnotation(
            ImageServer<BufferedImage> colorServer, ROI roi, String calibrationPath, PPMAnalysisSet analysisSet)
            throws Exception {

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        RegionRequest request = RegionRequest.createInstance(colorServer.getPath(), 1.0, x, y, w, h);

        NDArray sumNDArray = null;
        NDArray birefNDArray = null;
        NDArray roiNDArray = null;

        try {
            BufferedImage colorRegion = colorServer.readRegion(request);
            sumNDArray = PPMPerpendicularityWorkflow.bufferedImageToRGBNDArray(colorRegion);

            birefNDArray = readBirefNDArray(analysisSet, x, y, w, h);

            // Create ROI mask
            roiNDArray = PPMPolarityPlotWorkflow.createROIMaskNDArray(roi, x, y, w, h);

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

            Task task = ApposePPMService.getInstance().runTask("run_polarity", inputs);
            String json = (String) task.outputs.get("result_json");

            Gson gson = new Gson();
            JsonObject result = gson.fromJson(json, JsonObject.class);

            if (result.has("error") && !result.get("error").isJsonNull()) {
                throw new RuntimeException(
                        "Python error: " + result.get("error").getAsString());
            }

            return result;
        } finally {
            if (sumNDArray != null) sumNDArray.close();
            if (birefNDArray != null) birefNDArray.close();
            if (roiNDArray != null) roiNDArray.close();
        }
    }

    private static JsonObject runPerpendicularityForAnnotation(
            ImageServer<BufferedImage> colorServer,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            double pixelSizeUm,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles)
            throws Exception {

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        // Expand for dilation zone
        double dilationPx = dilationUm / pixelSizeUm;
        int pad = (int) Math.ceil(dilationPx) + 5;
        int expandedX = Math.max(0, x - pad);
        int expandedY = Math.max(0, y - pad);
        int expandedW = Math.min(colorServer.getWidth() - expandedX, w + 2 * pad);
        int expandedH = Math.min(colorServer.getHeight() - expandedY, h + 2 * pad);

        RegionRequest request =
                RegionRequest.createInstance(colorServer.getPath(), 1.0, expandedX, expandedY, expandedW, expandedH);

        NDArray sumNDArray = null;
        NDArray birefNDArray = null;

        try {
            BufferedImage colorRegion = colorServer.readRegion(request);
            sumNDArray = PPMPerpendicularityWorkflow.bufferedImageToRGBNDArray(colorRegion);

            birefNDArray = readBirefNDArray(analysisSet, expandedX, expandedY, expandedW, expandedH);

            // Export ROI as GeoJSON string with offset
            String geojsonString = PPMPerpendicularityWorkflow.exportRoiAsGeoJSONString(roi, expandedX, expandedY);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("sum_image", sumNDArray);
            inputs.put("calibration_path", calibrationPath);
            inputs.put("geojson_boundary", geojsonString);
            inputs.put("pixel_size_um", pixelSizeUm);
            inputs.put("dilation_um", dilationUm);
            inputs.put("zone_mode", zoneMode);
            inputs.put("tacs_threshold", tacsThreshold);
            inputs.put("fill_holes", fillHoles);
            inputs.put("saturation_threshold", PPMPreferences.getSaturationThreshold());
            inputs.put("value_threshold", PPMPreferences.getValueThreshold());
            inputs.put("image_width", expandedW);
            inputs.put("image_height", expandedH);

            if (birefNDArray != null) {
                inputs.put("biref_image", birefNDArray);
                inputs.put("biref_threshold", getBirefThreshold());
            }

            Task task = ApposePPMService.getInstance().runTask("run_perpendicularity", inputs);
            String json = (String) task.outputs.get("result_json");

            Gson gson = new Gson();
            JsonObject result = gson.fromJson(json, JsonObject.class);

            if (result.has("error") && !result.get("error").isJsonNull()) {
                throw new RuntimeException(
                        "Python error: " + result.get("error").getAsString());
            }

            return result;
        } finally {
            if (sumNDArray != null) sumNDArray.close();
            if (birefNDArray != null) birefNDArray.close();
        }
    }

    // ========================================================================
    // Shared helpers
    // ========================================================================

    /**
     * Reads a birefringence region as an NDArray, or returns null if unavailable.
     */
    @SuppressWarnings("unchecked")
    private static NDArray readBirefNDArray(PPMAnalysisSet analysisSet, int x, int y, int w, int h) {
        if (analysisSet == null || !analysisSet.hasBirefImage()) return null;

        try {
            ImageData<BufferedImage> birefData = (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
            ImageServer<BufferedImage> birefServer = birefData.getServer();
            RegionRequest birefRequest = RegionRequest.createInstance(birefServer.getPath(), 1.0, x, y, w, h);
            BufferedImage birefRegion = birefServer.readRegion(birefRequest);
            birefServer.close();
            return PPMPerpendicularityWorkflow.bufferedImageToGray16NDArray(birefRegion);
        } catch (Exception e) {
            logger.debug("Could not read biref region: {}", e.getMessage());
            return null;
        }
    }

    private static void updateProgress(
            javafx.scene.control.Label label, javafx.scene.control.ProgressBar bar, String text, double progress) {
        Platform.runLater(() -> {
            label.setText(text);
            bar.setProgress(progress);
        });
    }

    private static boolean isBirefImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("biref")) return true;
        return imageName != null && imageName.contains("biref");
    }
}
