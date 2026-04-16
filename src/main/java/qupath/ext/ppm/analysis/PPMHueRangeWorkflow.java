package qupath.ext.ppm.analysis;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.PPMPreferences;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.ppm.analysis.PPMImageSetDiscovery.PPMAnalysisSet;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

/**
 * Workflow for the PPM hue range filter overlay.
 *
 * <p>Sets up a {@link PPMHueRangeOverlay} on the current viewer and shows
 * a {@link PPMHueRangePanel} control window. The overlay highlights pixels
 * whose fiber angle falls within the user-specified range.</p>
 *
 * <p>The angle computation uses Java-side {@link PPMCalibration#hueToAngle(double)}
 * for interactive speed. The equivalent Python function is
 * {@code ppm_library.analysis.region_analysis.filter_angles_by_range()}.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMHueRangeWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMHueRangeWorkflow.class);

    private static final ExecutorService DETECTION_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PPM-DetectionCreator");
        t.setDaemon(true);
        return t;
    });

    private static Stage controlWindow;
    private static PPMHueRangeOverlay activeOverlay;

    private PPMHueRangeWorkflow() {}

    /**
     * Main entry point. Shows the hue range filter control panel and sets up the overlay.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run hue range filter workflow", e);
                Dialogs.showErrorMessage(
                        "PPM Hue Range Filter",
                        DocumentationHelper.withDocLink("Error: " + e.getMessage(), "ppmHueRangeFilter"));
            }
        });
    }

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage(
                    "PPM Hue Range Filter",
                    DocumentationHelper.withDocLink("QuPath is not available.", "ppmHueRangeFilter"));
            return;
        }

        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(
                    "PPM Hue Range Filter", DocumentationHelper.withDocLink("No image is open.", "ppmHueRangeFilter"));
            return;
        }

        ImageServer<BufferedImage> server = imageData.getServer();

        // Find calibration
        Project<BufferedImage> project = gui.getProject();
        ProjectImageEntry<BufferedImage> currentEntry = project != null ? project.getEntry(imageData) : null;

        PPMAnalysisSet analysisSet = null;
        if (currentEntry != null && project != null) {
            analysisSet = PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project);
        }

        String calibrationPath = null;
        if (analysisSet != null && analysisSet.hasCalibration()) {
            calibrationPath = analysisSet.calibrationPath;
        }
        if (calibrationPath == null && currentEntry != null) {
            calibrationPath = ImageMetadataManager.getPPMCalibration(currentEntry);
        }
        if (calibrationPath == null) {
            String activePath = PPMPreferences.getActiveCalibrationPath();
            if (activePath != null && !activePath.isEmpty()) {
                calibrationPath = activePath;
            }
        }
        if (calibrationPath == null) {
            Dialogs.showErrorMessage(
                    "PPM Hue Range Filter",
                    DocumentationHelper.withDocLink(
                            "No PPM calibration found. Run sunburst calibration first.", "ppmHueRangeFilter"));
            return;
        }

        // Load calibration
        PPMCalibration calibration;
        try {
            calibration = PPMCalibration.load(calibrationPath);
        } catch (Exception e) {
            logger.error("Failed to load calibration from: {}", calibrationPath, e);
            Dialogs.showErrorMessage(
                    "PPM Hue Range Filter",
                    DocumentationHelper.withDocLink(
                            "Failed to load calibration: " + e.getMessage(), "ppmHueRangeFilter"));
            return;
        }

        QuPathViewer viewer = gui.getViewer();

        // Remove any previous overlay
        cleanup();

        // Create overlay
        PPMHueRangeOverlay overlay = new PPMHueRangeOverlay(viewer);
        overlay.setCalibration(calibration);
        overlay.setServer(server);
        overlay.setOpacity(0.5);
        overlay.setActive(true);

        // Add to viewer
        viewer.getCustomOverlayLayers().add(overlay);
        activeOverlay = overlay;

        // Create control panel
        PPMHueRangePanel panel = new PPMHueRangePanel();
        panel.setCalibration(calibration);

        // Wire overlay visibility toggle
        panel.setOnOverlayVisibilityChanged(visible -> {
            overlay.setActive(visible);
            viewer.repaint();
        });

        // Wire stats updates
        overlay.setStatsListener((matching, total) -> Platform.runLater(() -> panel.updateStats(matching, total)));

        // Wire parameter changes
        panel.setOnParametersChanged(() -> {
            overlay.setAngleRange(panel.getAngleLow(), panel.getAngleHigh());
            overlay.setSaturationThreshold(panel.getSaturationThreshold());
            overlay.setValueThreshold(panel.getValueThreshold());
            overlay.setMinRgbIntensity(panel.getMinRgbIntensity());
            overlay.setHighlightRGB(panel.getHighlightRGB());
            overlay.setOpacity(panel.getOverlayOpacity());
            overlay.recompute();
        });

        // Wire clear button
        panel.setOnClear(() -> {
            cleanup();
            viewer.repaint();
        });

        // Wire detection creation
        panel.setOnCreateDetections(() -> {
            // Persist threshold values for next session
            PPMPreferences.setSaturationThreshold(panel.getSaturationThreshold());
            PPMPreferences.setValueThreshold(panel.getValueThreshold());
            createDetections(panel, server, calibration, imageData);
        });

        // Show control window
        if (controlWindow == null || !controlWindow.isShowing()) {
            controlWindow = new Stage();
            controlWindow.setTitle("PPM Hue Range Filter");
            controlWindow.initOwner(gui.getStage());
            controlWindow.setAlwaysOnTop(false);
            controlWindow.setOnCloseRequest(e -> cleanup());
        }
        javafx.scene.control.Button docButton = DocumentationHelper.createHelpButton("ppmHueRangeFilter");
        javafx.scene.layout.VBox wrapper = new javafx.scene.layout.VBox();
        if (docButton != null) {
            javafx.scene.layout.HBox helpBar = new javafx.scene.layout.HBox(docButton);
            helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            helpBar.setPadding(new javafx.geometry.Insets(4, 8, 0, 0));
            wrapper.getChildren().add(helpBar);
        }
        javafx.scene.layout.VBox.setVgrow(panel, javafx.scene.layout.Priority.ALWAYS);
        wrapper.getChildren().add(panel);
        controlWindow.setScene(new Scene(wrapper, 380, 580));
        controlWindow.show();
        controlWindow.toFront();

        // Trigger initial computation with default parameters
        overlay.setAngleRange(panel.getAngleLow(), panel.getAngleHigh());
        overlay.setSaturationThreshold(panel.getSaturationThreshold());
        overlay.setValueThreshold(panel.getValueThreshold());
        overlay.setMinRgbIntensity(panel.getMinRgbIntensity());
        overlay.setHighlightRGB(panel.getHighlightRGB());
        overlay.setOpacity(panel.getOverlayOpacity());
        overlay.recompute();

        logger.info(
                "PPM hue range filter overlay active on {}",
                server.getMetadata().getName());
    }

    /**
     * Creates detection objects from the current angle range settings.
     * Runs on a background thread to avoid blocking the UI.
     */
    private static void createDetections(
            PPMHueRangePanel panel,
            ImageServer<BufferedImage> server,
            PPMCalibration calibration,
            ImageData<BufferedImage> imageData) {

        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        // Get selected annotation ROI (if any)
        PathObject selected = hierarchy.getSelectionModel().getSelectedObject();
        ROI parentROI = null;
        if (selected != null && selected.isAnnotation() && selected.hasROI()) {
            parentROI = selected.getROI();
        }

        // Get pixel size from server metadata
        double pixelSizeUm = server.getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(pixelSizeUm) || pixelSizeUm <= 0) {
            pixelSizeUm = 1.0;
            logger.warn("No pixel calibration available, using 1.0 um/px");
        }

        // Snapshot parameters from FX thread
        float angleLow = panel.getAngleLow();
        float angleHigh = panel.getAngleHigh();
        float satThreshold = panel.getSaturationThreshold();
        float valThreshold = panel.getValueThreshold();
        double downsample = panel.getDetectionDownsample();
        double minAreaUm2 = panel.getMinAreaUm2();
        final ROI roi = parentROI;
        final double pxSize = pixelSizeUm;

        panel.setCreateDetectionsEnabled(false);

        DETECTION_EXECUTOR.submit(() -> {
            try {
                int count = PPMDetectionCreator.create(
                        server,
                        calibration,
                        angleLow,
                        angleHigh,
                        satThreshold,
                        valThreshold,
                        downsample,
                        roi,
                        minAreaUm2,
                        hierarchy,
                        pxSize);

                Platform.runLater(() -> {
                    panel.setCreateDetectionsEnabled(true);
                    if (count > 0) {
                        Dialogs.showInfoNotification(
                                "PPM Detections",
                                String.format(
                                        "Created %d detection%s (%.0f-%.0f deg)",
                                        count, count == 1 ? "" : "s", angleLow, angleHigh));
                    } else {
                        Dialogs.showInfoNotification("PPM Detections", "No matching regions found.");
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to create PPM detections", e);
                Platform.runLater(() -> {
                    panel.setCreateDetectionsEnabled(true);
                    Dialogs.showErrorMessage(
                            "PPM Detections",
                            DocumentationHelper.withDocLink(
                                    "Error creating detections: " + e.getMessage(), "ppmHueRangeFilter"));
                });
            }
        });
    }

    /**
     * Removes the overlay and closes the control window.
     */
    private static void cleanup() {
        if (activeOverlay != null) {
            QuPathGUI gui = QPEx.getQuPath();
            if (gui != null) {
                QuPathViewer viewer = gui.getViewer();
                if (viewer != null) {
                    viewer.getCustomOverlayLayers().remove(activeOverlay);
                }
            }
            activeOverlay.dispose();
            activeOverlay = null;
        }
    }
}
