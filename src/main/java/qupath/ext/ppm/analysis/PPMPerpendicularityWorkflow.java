package qupath.ext.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
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
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ml.pixel.PixelClassifierTools;

/**
 * Workflow for surface perpendicularity analysis of fiber orientation relative
 * to annotation boundaries.
 *
 * <p>Implements two analysis approaches:</p>
 * <ul>
 *   <li><b>Simple perpendicularity</b>: Distance-transform based surface normals,
 *       average deviation angle per pixel.</li>
 *   <li><b>PS-TACS scoring</b>: Per-contour-pixel TACS scoring with Gaussian
 *       distance weighting, based on Qian et al. (2025).</li>
 * </ul>
 *
 * <p>All computation is performed by {@code ppm_library.analysis.surface_analysis}
 * (Python) via the CLI. Java handles QuPath I/O, annotation discovery, GeoJSON
 * export, and results display.</p>
 *
 * <p>Reference: Qian et al., "Computationally Enabled Polychromatic Polarized
 * Imaging Enables Mapping of Matrix Architectures that Promote Pancreatic Ductal
 * Adenocarcinoma Dissemination", Am J Pathol 2025; 195:1242-1253.
 * DOI: <a href="https://doi.org/10.1016/j.ajpath.2025.04.017">10.1016/j.ajpath.2025.04.017</a></p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMPerpendicularityWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMPerpendicularityWorkflow.class);

    // Analysis parameters now read from PPMPreferences (configurable in QuPath Preferences)
    private static double getBirefThreshold() {
        return PPMPreferences.getBirefringenceThreshold();
    }

    private static double getDefaultDilationUm() {
        return PPMPreferences.getDilationUm();
    }

    private static double getDefaultTacsThreshold() {
        return PPMPreferences.getTacsThresholdDeg();
    }

    /** Pixels within this margin of the image border are excluded from TACS polylines. */
    private static final double IMAGE_BORDER_MARGIN_PX = 3.0;

    // TACS-1 = bright yellow (sparse collagen)
    private static final int TACS1_COLOR = ColorTools.packRGB(255, 255, 0);
    // TACS-2 = neon green (parallel-to-boundary; benign / healthy connotation)
    private static final int TACS2_COLOR = ColorTools.packRGB(0, 255, 65);
    // TACS-3 = neon orange (perpendicular-to-boundary; invasive connotation)
    private static final int TACS3_COLOR = ColorTools.packRGB(255, 100, 0);

    private static Stage resultWindow;
    private static PPMPerpendicularityPanel resultPanel;

    /** Bundles the Python JSON result with the region offset for coordinate translation. */
    private static class AnnotationResult {
        final JsonObject json;
        final int offsetX;
        final int offsetY;
        final int regionW;
        final int regionH;
        final byte[] foregroundMask; // null if not available

        AnnotationResult(JsonObject json, int offsetX, int offsetY, int regionW, int regionH, byte[] foregroundMask) {
            this.json = json;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.regionW = regionW;
            this.regionH = regionH;
            this.foregroundMask = foregroundMask;
        }
    }

    private PPMPerpendicularityWorkflow() {}

    /**
     * Main entry point. Shows a configuration dialog, then runs analysis.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run perpendicularity workflow", e);
                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        DocumentationHelper.withDocLink("Error: " + e.getMessage(), "ppmPerpendicularity"));
            }
        });
    }

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    DocumentationHelper.withDocLink("QuPath is not available.", "ppmPerpendicularity"));
            return;
        }

        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    DocumentationHelper.withDocLink("No image is open.", "ppmPerpendicularity"));
            return;
        }

        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    DocumentationHelper.withDocLink(
                            "A QuPath project is required for this analysis.\n" + "Create or open a project first.",
                            "ppmPerpendicularity"));
            return;
        }

        // Validate the current image is a PPM color (angle) image, not the biref image.
        // The analysis needs an RGB image for PPM angle computation via calibration.
        // Any angle image (positive, negative, crossed, uncrossed) works -- they are
        // pixel-identical for orientation purposes. The biref image is grayscale
        // and cannot be used as the color source.
        ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(imageData);
        if (currentEntry != null) {
            String angle = currentEntry.getMetadata().get("angle");
            String imageName = currentEntry.getImageName();
            boolean isBiref = (angle != null && angle.toLowerCase().contains("biref"))
                    || (imageName != null && imageName.toLowerCase().contains("biref"));

            if (isBiref) {
                // Try to identify an angle image for the user
                PPMAnalysisSet analysisSetCheck = PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project);
                String angleHint = "";
                if (analysisSetCheck != null && !analysisSetCheck.angleImages.isEmpty()) {
                    angleHint = "\n\nAngle images in this set:\n";
                    for (var angleImg : analysisSetCheck.angleImages) {
                        angleHint += "  - " + angleImg.getImageName() + "\n";
                    }
                }

                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        DocumentationHelper.withDocLink(
                                "This analysis requires a PPM color (angle) image.\n"
                                        + "The currently open image ("
                                        + (imageName != null ? imageName : "unknown")
                                        + ") is the birefringence image, which is grayscale.\n\n"
                                        + "Open any angle image from this set (e.g. positive or\n"
                                        + "negative angle), draw boundary annotations on it,\n"
                                        + "then run this analysis again. The birefringence image\n"
                                        + "will be found automatically for threshold masking."
                                        + angleHint,
                                "ppmPerpendicularity"));
                return;
            }
        }

        // Find calibration
        String calibrationPath = findCalibrationPath(currentEntry, project);
        if (calibrationPath == null) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    DocumentationHelper.withDocLink(
                            "No PPM calibration found. Run sunburst calibration first.", "ppmPerpendicularity"));
            return;
        }

        // Get pixel calibration
        PixelCalibration pixelCal = imageData.getServer().getPixelCalibration();
        double pixelSizeUm;
        if (pixelCal.hasPixelSizeMicrons()) {
            pixelSizeUm = pixelCal.getAveragedPixelSizeMicrons();
        } else {
            // Ask user
            String input = Dialogs.showInputDialog(
                    "Pixel Size Required",
                    "Image has no pixel size metadata.\n" + "Enter pixel size in microns:",
                    "1.0");
            if (input == null || input.isEmpty()) return;
            try {
                pixelSizeUm = Double.parseDouble(input.trim());
            } catch (NumberFormatException e) {
                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        DocumentationHelper.withDocLink("Invalid pixel size: " + input, "ppmPerpendicularity"));
                return;
            }
            if (pixelSizeUm <= 0) {
                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        DocumentationHelper.withDocLink("Pixel size must be positive.", "ppmPerpendicularity"));
                return;
            }
        }

        // Collect available annotation classes
        Collection<PathObject> allAnnotations = imageData.getHierarchy().getAnnotationObjects();
        List<String> classNames = allAnnotations.stream()
                .map(PathObject::getPathClass)
                .filter(pc -> pc != null)
                .map(PathClass::toString)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (classNames.isEmpty()) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    DocumentationHelper.withDocLink(
                            "No classified annotations found.\n" + "Assign a class to boundary annotations first.",
                            "ppmPerpendicularity"));
            return;
        }

        // Show configuration dialog
        showConfigDialog(
                gui, imageData, project, currentEntry, calibrationPath, pixelSizeUm, classNames, allAnnotations);
    }

    private static void showConfigDialog(
            QuPathGUI gui,
            ImageData<BufferedImage> imageData,
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> currentEntry,
            String calibrationPath,
            double pixelSizeUm,
            List<String> classNames,
            Collection<PathObject> allAnnotations) {

        Stage dialog = new Stage();
        dialog.setTitle("Surface Perpendicularity Analysis");
        dialog.initOwner(gui.getStage());

        Button helpButton = DocumentationHelper.createHelpButton("ppmPerpendicularity");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));

        int row = 0;

        // Image info header
        String colorImageName = currentEntry != null ? currentEntry.getImageName() : "unknown";
        PPMAnalysisSet previewSet =
                currentEntry != null ? PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project) : null;
        Label imageInfoLabel = new Label("Color image: " + colorImageName
                + (previewSet != null && previewSet.hasBirefImage()
                        ? "\nBirefringence: " + previewSet.birefImage.getImageName()
                        : "\nBirefringence: not found (threshold masking unavailable)"));
        imageInfoLabel.setWrapText(true);
        imageInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        grid.add(imageInfoLabel, 0, row, 3, 1);
        row++;

        // Annotation class selector
        Label boundaryClassLabel = new Label("Boundary annotation class:");
        boundaryClassLabel.setTooltip(
                new Tooltip("Annotation class that defines the boundary for perpendicularity analysis"));
        grid.add(boundaryClassLabel, 0, row);
        ChoiceBox<String> classChoice = new ChoiceBox<>();
        classChoice.getItems().addAll(classNames);
        String rememberedClass = PPMPreferences.getPerpBoundaryClass();
        if (rememberedClass != null && !rememberedClass.isEmpty() && classNames.contains(rememberedClass)) {
            classChoice.setValue(rememberedClass);
        } else {
            classChoice.setValue(classNames.get(0));
        }
        grid.add(classChoice, 1, row);

        // Annotation count label
        Label countLabel = new Label();
        grid.add(countLabel, 2, row);
        row++;

        // Update count when class changes
        Runnable updateCount = () -> {
            String selected = classChoice.getValue();
            if (selected == null) return;
            long count = allAnnotations.stream()
                    .filter(a -> a.getPathClass() != null
                            && a.getPathClass().toString().equals(selected))
                    .count();
            countLabel.setText("(" + count + " annotations)");
        };
        classChoice.setOnAction(e -> updateCount.run());
        updateCount.run();

        // Dilation
        Label borderZoneLabel = new Label("Border zone width (um):");
        borderZoneLabel.setTooltip(
                new Tooltip("Distance from the boundary within which fibers are analyzed (1-500 um)"));
        grid.add(borderZoneLabel, 0, row);
        Spinner<Double> dilationSpinner =
                new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 500, getDefaultDilationUm(), 5));
        dilationSpinner.setEditable(true);
        Tooltip dilationTip = new Tooltip("Range: 1-500 um. Distance from the boundary annotation\n"
                + "edge within which fibers are analyzed. Only fibers within\n"
                + "this zone are included in TACS classification.\n\n"
                + "Increase for broad stromal regions; decrease for tight\n"
                + "peri-tumoral analysis near the boundary.");
        dilationTip.setShowDelay(Duration.millis(400));
        dilationSpinner.setTooltip(dilationTip);
        grid.add(dilationSpinner, 1, row);
        row++;

        // Zone mode
        Label zoneModeLabel = new Label("Zone mode:");
        zoneModeLabel.setTooltip(new Tooltip("Which side of the boundary to analyze (outside, inside, or both)"));
        grid.add(zoneModeLabel, 0, row);
        ChoiceBox<String> zoneChoice = new ChoiceBox<>();
        zoneChoice.getItems().addAll("outside", "inside", "both");
        String rememberedZone = PPMPreferences.getZoneMode();
        zoneChoice.setValue(zoneChoice.getItems().contains(rememberedZone) ? rememberedZone : "outside");
        Tooltip zoneModeTip = new Tooltip("Which side of the boundary to analyze:\n"
                + "  'outside' - stroma outside the boundary (most common)\n"
                + "  'inside' - tissue inside the boundary annotation\n"
                + "  'both' - fibers on both sides of the boundary\n\n"
                + "For tumor/stroma boundaries, 'outside' analyzes the\n"
                + "peri-tumoral stroma where TACS patterns are most relevant.");
        zoneModeTip.setShowDelay(Duration.millis(400));
        zoneChoice.setTooltip(zoneModeTip);
        grid.add(zoneChoice, 1, row);
        row++;

        // TACS threshold
        Label tacsThreshLabel = new Label("TACS threshold (deg from normal):");
        tacsThreshLabel.setTooltip(new Tooltip("Angle cutoff for TACS classification (5-85 deg)"));
        grid.add(tacsThreshLabel, 0, row);
        Spinner<Double> tacsSpinner =
                new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(5, 85, getDefaultTacsThreshold(), 5));
        tacsSpinner.setEditable(true);
        Tooltip tacsTip = new Tooltip("Range: 5-85 degrees. Angle cutoff for TACS classification.\n"
                + "Fibers within this angle of perpendicular -> TACS-3.\n"
                + "Fibers within this angle of parallel -> TACS-2.\n"
                + "Fibers in between are unclassified.\n\n"
                + "Lower values = stricter classification (fewer fibers classified).\n"
                + "30 deg is a common default for collagen alignment studies.");
        tacsTip.setShowDelay(Duration.millis(400));
        tacsSpinner.setTooltip(tacsTip);
        grid.add(tacsSpinner, 1, row);
        row++;

        // Fill holes
        CheckBox fillHolesBox = new CheckBox("Fill holes in boundary");
        fillHolesBox.setSelected(PPMPreferences.getFillHoles());
        Tooltip fillHolesTip = new Tooltip("Fill internal holes in the boundary annotation before\n"
                + "computing perpendicularity. Enable this (default) to treat\n"
                + "the boundary as a solid region. Disable only if the holes\n"
                + "are intentional features to analyze around.");
        fillHolesTip.setShowDelay(Duration.millis(400));
        fillHolesBox.setTooltip(fillHolesTip);
        grid.add(fillHolesBox, 0, row, 2, 1);
        row++;

        // Minimum polyline length
        Label minSegLabel = new Label("Min TACS segment length (px):");
        minSegLabel.setTooltip(new Tooltip("Minimum contour points for a TACS polyline segment (2-500 px)"));
        grid.add(minSegLabel, 0, row);
        Spinner<Integer> minLengthSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 500, PPMPreferences.getMinPolylineLengthPx(), 5));
        minLengthSpinner.setEditable(true);
        Tooltip minLenTip = new Tooltip("Range: 2-500 pixels. Minimum number of contour points\n"
                + "for a TACS polyline segment to be drawn. Short fragments\n"
                + "are usually noise and clutter the overlay.\n\n"
                + "Increase to show only longer, more reliable segments.\n"
                + "Decrease to see finer-grained classification detail.");
        minLenTip.setShowDelay(Duration.millis(400));
        minLengthSpinner.setTooltip(minLenTip);
        grid.add(minLengthSpinner, 1, row);
        row++;

        // --- Extended TACS section ---
        Label extTacsHeader = new Label("Extended TACS (TACS-1/2/3)");
        extTacsHeader.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 2 0;");
        grid.add(extTacsHeader, 0, row, 3, 1);
        row++;

        CheckBox extendedTacsBox = new CheckBox("Enable TACS-1 classification");
        extendedTacsBox.setSelected(PPMPreferences.getExtendedTACSEnabled());
        Tooltip extTacsTip = new Tooltip("Adds TACS-1 classification for boundary regions where\n"
                + "collagen density is below threshold (sparse/absent).\n"
                + "TACS-1 = sparse collagen (Provenzano et al. 2006).\n\n"
                + "PS-TACS results (TACS-2/3, Qian et al.) are always\n"
                + "shown regardless. This adds TACS-1 polylines (yellow)\n"
                + "and an Extended TACS section in the results panel.");
        extTacsTip.setShowDelay(Duration.millis(400));
        extendedTacsBox.setTooltip(extTacsTip);
        grid.add(extendedTacsBox, 0, row, 2, 1);
        row++;

        Label densityLabel = new Label("Min collagen density:");
        densityLabel.setTooltip(new Tooltip("Normalized collagen density threshold for TACS-1 demotion (0.01-0.50).\n"
                + "INCREASE -> more contour pixels demoted from TACS-2/3 to TACS-1 (sparse).\n"
                + "DECREASE -> fewer pixels demoted; TACS-2/3 stays in more places."));
        grid.add(densityLabel, 0, row);
        Spinner<Double> densitySpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.01, 0.5, PPMPreferences.getMinCollagenDensity(), 0.01));
        densitySpinner.setEditable(true);
        Tooltip densityTip = new Tooltip("Range: 0.01-0.50. Normalized collagen density threshold.\n"
                + "Contour pixels with less than this fraction of the peak\n"
                + "observed density along this boundary are reclassified as\n"
                + "TACS-1 (sparse collagen). Does NOT exclude pixels from analysis --\n"
                + "it relabels them.\n\n"
                + "0.10 = regions with <10% of peak density -> TACS-1.\n\n"
                + "INCREASE -> more contour pixels demoted to TACS-1 (more sparse-labelled).\n"
                + "DECREASE -> fewer demoted; TACS-2/3 retained more widely.");
        densityTip.setShowDelay(Duration.millis(400));
        densitySpinner.setTooltip(densityTip);
        grid.add(densitySpinner, 1, row);
        row++;

        Label signalLabel = new Label("Min signal threshold:");
        signalLabel.setTooltip(new Tooltip("Normalized density below which a contour pixel is dropped (0-0.20).\n"
                + "INCREASE -> more contour pixels dropped as Unclassified (no polyline drawn).\n"
                + "DECREASE -> more pixels kept (classified into TACS-1/2/3)."));
        grid.add(signalLabel, 0, row);
        Spinner<Double> signalSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.0, 0.2, PPMPreferences.getMinSignalThreshold(), 0.01));
        signalSpinner.setEditable(true);
        Tooltip signalTip = new Tooltip("Range: 0-0.20. Normalized density below which a contour\n"
                + "pixel is considered to have NO collagen signal at all\n"
                + "(Unclassified). These segments get no polyline overlay,\n"
                + "so the boundary line has a gap there.\n\n"
                + "This filters out noise and non-collagen tissue at the boundary.\n\n"
                + "INCREASE -> more contour pixels EXCLUDED from classification (more gaps).\n"
                + "DECREASE -> more pixels INCLUDED in TACS-1/2/3 (set to 0 to classify everything).\n"
                + "Default 0.02 (2% of peak density).");
        signalTip.setShowDelay(Duration.millis(400));
        signalSpinner.setTooltip(signalTip);
        grid.add(signalSpinner, 1, row);
        row++;

        // Disable extended TACS controls when checkbox is off (matches restored state)
        boolean extOn = extendedTacsBox.isSelected();
        densityLabel.setDisable(!extOn);
        densitySpinner.setDisable(!extOn);
        signalLabel.setDisable(!extOn);
        signalSpinner.setDisable(!extOn);
        extendedTacsBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            densityLabel.setDisable(!newVal);
            densitySpinner.setDisable(!newVal);
            signalLabel.setDisable(!newVal);
            signalSpinner.setDisable(!newVal);
        });

        // --- Smoothing & cleanup section ---
        Label smoothHeader = new Label("Smoothing & Cleanup");
        smoothHeader.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 2 0;");
        grid.add(smoothHeader, 0, row, 3, 1);
        row++;

        Label bndSmoothLabel = new Label("Boundary smoothing (px):");
        bndSmoothLabel.setTooltip(new Tooltip("Gaussian sigma for smoothing the boundary contour (0-20 px)"));
        grid.add(bndSmoothLabel, 0, row);
        Spinner<Double> boundarySigmaSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 20, PPMPreferences.getBoundarySmoothing(), 0.5));
        boundarySigmaSpinner.setEditable(true);
        Tooltip bndSigmaTip = new Tooltip("Range: 0-20 pixels. Gaussian sigma for smoothing the\n"
                + "boundary contour before computing surface normals.\n"
                + "Removes pixel-level staircase artifacts.\n\n"
                + "Decrease for finer boundary detail. Set 0 to disable.\n"
                + "Default 5.0 works for smooth tumor boundaries.");
        bndSigmaTip.setShowDelay(Duration.millis(400));
        boundarySigmaSpinner.setTooltip(bndSigmaTip);
        grid.add(boundarySigmaSpinner, 1, row);
        row++;

        Label tacsSmoothLabel = new Label("TACS contour smoothing:");
        tacsSmoothLabel.setTooltip(
                new Tooltip("Moving average window for TACS score smoothing along the boundary (1-50)"));
        grid.add(tacsSmoothLabel, 0, row);
        Spinner<Integer> smoothWindowSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50, PPMPreferences.getTACSContourSmoothing(), 1));
        smoothWindowSpinner.setEditable(true);
        Tooltip smoothWinTip = new Tooltip("Range: 1-50. Moving average window for TACS contour\n"
                + "score smoothing. Larger values smooth out noisy\n"
                + "TACS-2/3 classification along the boundary.\n\n"
                + "Decrease for finer classification detail.\n"
                + "Set 1 to disable smoothing. Default 10.");
        smoothWinTip.setShowDelay(Duration.millis(400));
        smoothWindowSpinner.setTooltip(smoothWinTip);
        grid.add(smoothWindowSpinner, 1, row);
        row++;

        Label minColAreaLabel = new Label("Min collagen area (px):");
        minColAreaLabel.setTooltip(new Tooltip("Connected components smaller than this are removed (0-5000 px)"));
        grid.add(minColAreaLabel, 0, row);
        Spinner<Integer> minAreaSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 5000, PPMPreferences.getMinCollagenArea(), 25));
        minAreaSpinner.setEditable(true);
        Tooltip minAreaTip = new Tooltip("Range: 0-5000 pixels. Connected components smaller than\n"
                + "this are removed from the foreground mask.\n\n"
                + "Decrease to keep thin collagen strands and small\n"
                + "fragments. Set 0 to disable cleanup. Default 100.");
        minAreaTip.setShowDelay(Duration.millis(400));
        minAreaSpinner.setTooltip(minAreaTip);
        grid.add(minAreaSpinner, 1, row);
        row++;

        Label maskSigmaLabel = new Label("Mask smoothing sigma:");
        maskSigmaLabel.setTooltip(new Tooltip("Gaussian sigma for smoothing the foreground mask (0-10 px)"));
        grid.add(maskSigmaLabel, 0, row);
        Spinner<Double> maskSigmaSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 10, PPMPreferences.getMaskSmoothingSigma(), 0.5));
        maskSigmaSpinner.setEditable(true);
        Tooltip maskSigmaTip = new Tooltip("Range: 0-10 pixels. Gaussian sigma for smoothing the\n"
                + "foreground mask before removing small objects.\n\n"
                + "Decrease to preserve thin collagen features.\n"
                + "Set 0 to disable smoothing. Default 2.0.");
        maskSigmaTip.setShowDelay(Duration.millis(400));
        maskSigmaSpinner.setTooltip(maskSigmaTip);
        grid.add(maskSigmaSpinner, 1, row);
        row++;

        // --- Window analysis section ---
        Label windowHeader = new Label("Window Analysis");
        windowHeader.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 2 0;");
        grid.add(windowHeader, 0, row, 3, 1);
        row++;

        CheckBox windowEnabledCheck = new CheckBox("Enable moving-window alignment");
        windowEnabledCheck.setSelected(PPMPreferences.getWindowAnalysisEnabled());
        Tooltip windowEnabledTip = new Tooltip("Aggregate per-pixel fiber orientations into a grid of\n"
                + "windows over the analysed region. Each window gets a\n"
                + "dominant orientation and an axial order parameter\n"
                + "(0 = isotropic, 1 = perfectly aligned). Two heatmap\n"
                + "PNGs are written next to results.json -- alignment\n"
                + "and dominant orientation -- and optionally one\n"
                + "PathObject per non-empty window is added to the\n"
                + "hierarchy.\n\n"
                + "Extension beyond the PS-TACS paper (Qian et al. 2025).");
        windowEnabledTip.setShowDelay(Duration.millis(400));
        windowEnabledCheck.setTooltip(windowEnabledTip);
        grid.add(windowEnabledCheck, 0, row, 3, 1);
        row++;

        Label windowSizeLabel = new Label("Window size (um):");
        windowSizeLabel.setStyle("-fx-padding: 0 0 0 16;");
        windowSizeLabel.setTooltip(new Tooltip("Side length of each square window in microns (1-200)."));
        grid.add(windowSizeLabel, 0, row);
        Spinner<Double> windowSizeSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, 200.0, PPMPreferences.getWindowSizeUm(), 1.0));
        windowSizeSpinner.setEditable(true);
        Tooltip windowSizeTip = new Tooltip("Range: 1-200 micrometers. Side length of each square\n"
                + "window. Smaller windows resolve finer alignment\n"
                + "variation but produce more objects (count grows\n"
                + "quadratically). Larger windows smooth over\n"
                + "structural detail. Default 15 um.");
        windowSizeTip.setShowDelay(Duration.millis(400));
        windowSizeSpinner.setTooltip(windowSizeTip);
        grid.add(windowSizeSpinner, 1, row);
        row++;

        Label windowOverlapLabel = new Label("Window overlap (%):");
        windowOverlapLabel.setStyle("-fx-padding: 0 0 0 16;");
        windowOverlapLabel.setTooltip(new Tooltip("Overlap between adjacent windows as a percentage (0-90)."));
        grid.add(windowOverlapLabel, 0, row);
        Spinner<Integer> windowOverlapSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 90, PPMPreferences.getWindowOverlapPercent(), 5));
        windowOverlapSpinner.setEditable(true);
        Tooltip windowOverlapTip = new Tooltip("Range: 0-90 percent. Higher overlap produces denser\n"
                + "grids (more objects, smoother heatmaps) but takes\n"
                + "more time to compute. 0 = non-overlapping (default),\n"
                + "50 = half-overlap.");
        windowOverlapTip.setShowDelay(Duration.millis(400));
        windowOverlapSpinner.setTooltip(windowOverlapTip);
        grid.add(windowOverlapSpinner, 1, row);
        row++;

        CheckBox windowObjectsCheck = new CheckBox("Create per-window detection objects");
        windowObjectsCheck.setStyle("-fx-padding: 0 0 0 16;");
        windowObjectsCheck.setSelected(PPMPreferences.getWindowCreateObjects());
        Tooltip windowObjectsTip = new Tooltip("When checked, one rectangular PathDetectionObject is\n"
                + "added to the hierarchy per non-empty window, carrying\n"
                + "the window's dominant orientation and order parameter\n"
                + "as measurements. Useful for exporting numeric data\n"
                + "from individual windows. Disabled by default because\n"
                + "small windows can produce thousands of objects.");
        windowObjectsTip.setShowDelay(Duration.millis(400));
        windowObjectsCheck.setTooltip(windowObjectsTip);
        grid.add(windowObjectsCheck, 0, row, 3, 1);
        row++;

        // Keep the sub-controls in sync with the enable toggle so they look
        // disabled when the user isn't using window analysis.
        Runnable refreshWindowEnabled = () -> {
            boolean on = windowEnabledCheck.isSelected();
            windowSizeSpinner.setDisable(!on);
            windowOverlapSpinner.setDisable(!on);
            windowObjectsCheck.setDisable(!on);
        };
        refreshWindowEnabled.run();
        windowEnabledCheck.selectedProperty().addListener((obs, oldV, newV) -> refreshWindowEnabled.run());

        // --- Foreground detection section ---
        Label filterHeader = new Label("Foreground Detection");
        filterHeader.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 2 0;");
        grid.add(filterHeader, 0, row, 3, 1);
        row++;

        // Radio toggle: threshold vs pixel classifier
        ToggleGroup foregroundToggle = new ToggleGroup();
        RadioButton thresholdRadio = new RadioButton("Intensity thresholds");
        thresholdRadio.setToggleGroup(foregroundToggle);
        boolean rememberedUseClassifier = PPMPreferences.getUseClassifier();
        thresholdRadio.setSelected(!rememberedUseClassifier);
        Tooltip threshRadioTip = new Tooltip("Use birefringence intensity and HSV thresholds to\n"
                + "identify collagen-containing pixels. Requires a biref\n"
                + "sibling image in the PPM analysis set.");
        threshRadioTip.setShowDelay(Duration.millis(400));
        thresholdRadio.setTooltip(threshRadioTip);
        RadioButton classifierRadio = new RadioButton("Pixel classifier");
        classifierRadio.setToggleGroup(foregroundToggle);
        classifierRadio.setSelected(rememberedUseClassifier);
        Tooltip classifierRadioTip = new Tooltip("Use a trained QuPath pixel classifier or thresholder\n"
                + "to define foreground. Create one via Classify > Pixel\n"
                + "classification in QuPath before using this option.");
        classifierRadioTip.setShowDelay(Duration.millis(400));
        classifierRadio.setTooltip(classifierRadioTip);
        HBox radioBox = new HBox(15, thresholdRadio, classifierRadio);
        grid.add(radioBox, 0, row, 3, 1);
        row++;

        // -- Threshold controls --
        Label birefLabel = new Label("Birefringence threshold:");
        birefLabel.setTooltip(new Tooltip("Minimum birefringence intensity for collagen-positive pixels (0-65535).\n"
                + "INCREASE -> more pixels EXCLUDED; DECREASE -> more pixels INCLUDED."));
        grid.add(birefLabel, 0, row);
        Spinner<Double> birefSpinner =
                new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 65535, getBirefThreshold(), 100));
        birefSpinner.setEditable(true);
        Tooltip birefTip = new Tooltip("Range: 0-65535 (16-bit image intensity). Minimum birefringence\n"
                + "intensity for a pixel to be considered PPM-positive (collagen).\n"
                + "Pixels below this value are EXCLUDED from analysis.\n"
                + "Only applies when a birefringence sibling image is found.\n\n"
                + "INCREASE -> more pixels EXCLUDED (stricter; excludes weak signal).\n"
                + "DECREASE -> more pixels INCLUDED (more permissive).\n"
                + "Typical range: 500-10000 depending on sample brightness and imaging conditions.");
        birefTip.setShowDelay(Duration.millis(400));
        birefSpinner.setTooltip(birefTip);
        grid.add(birefSpinner, 1, row);
        row++;

        Label birefBlurLabel = new Label("Gaussian blur (px):");
        birefBlurLabel.setStyle("-fx-padding: 0 0 0 16;");
        birefBlurLabel.setTooltip(
                new Tooltip("Pre-blur applied to the biref image before thresholding (0-10 px). 0 = no blur."));
        grid.add(birefBlurLabel, 0, row);
        Spinner<Double> birefBlurSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 10, PPMPreferences.getBirefBlurSigma(), 0.5));
        birefBlurSpinner.setEditable(true);
        birefBlurSpinner.setTooltip(birefBlurLabel.getTooltip());
        grid.add(birefBlurSpinner, 1, row);
        row++;

        Label satLabel = new Label("HSV saturation threshold:");
        satLabel.setTooltip(new Tooltip("Minimum HSV saturation for meaningful orientation (0-1).\n"
                + "INCREASE -> more pixels EXCLUDED; DECREASE -> more pixels INCLUDED."));
        grid.add(satLabel, 0, row);
        Spinner<Double> satSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1, PPMPreferences.getSaturationThreshold(), 0.05));
        satSpinner.setEditable(true);
        Tooltip satTip = new Tooltip("Range: 0-1. Minimum HSV saturation for a pixel to have a\n"
                + "meaningful hue/orientation. Filters out grayscale pixels\n"
                + "that lack color information for angle measurement.\n"
                + "Pixels below this value are EXCLUDED from analysis.\n\n"
                + "INCREASE -> more pixels EXCLUDED (stricter; excludes weakly coloured pixels).\n"
                + "DECREASE -> more pixels INCLUDED (more permissive).\n"
                + "Default 0.2 works for most samples.");
        satTip.setShowDelay(Duration.millis(400));
        satSpinner.setTooltip(satTip);
        grid.add(satSpinner, 1, row);
        row++;

        Label valLabel = new Label("HSV value threshold:");
        valLabel.setTooltip(new Tooltip("Minimum HSV brightness for included pixels (0-1).\n"
                + "INCREASE -> more pixels EXCLUDED; DECREASE -> more pixels INCLUDED."));
        grid.add(valLabel, 0, row);
        Spinner<Double> valSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1, PPMPreferences.getValueThreshold(), 0.05));
        valSpinner.setEditable(true);
        Tooltip valTip = new Tooltip("Range: 0-1. Minimum HSV brightness for a pixel to be\n"
                + "included in analysis. Filters out dark/shadow pixels.\n"
                + "Pixels below this value are EXCLUDED from analysis.\n\n"
                + "INCREASE -> more pixels EXCLUDED (stricter; excludes dim regions).\n"
                + "DECREASE -> more pixels INCLUDED (includes darker tissue).\n"
                + "Default 0.2 works for most samples.");
        valTip.setShowDelay(Duration.millis(400));
        valSpinner.setTooltip(valTip);
        grid.add(valSpinner, 1, row);
        row++;

        Label minIntLabel = new Label("Min pixel intensity:");
        minIntLabel.setTooltip(new Tooltip("Minimum max(R,G,B) to exclude dark absorbing tissue (0-255).\n"
                + "INCREASE -> more pixels EXCLUDED; DECREASE -> more pixels INCLUDED."));
        grid.add(minIntLabel, 0, row);
        Spinner<Integer> minIntensitySpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 255, PPMPreferences.getMinRgbIntensity(), 10));
        minIntensitySpinner.setEditable(true);
        Tooltip minIntTip = new Tooltip("Range: 0-255. Minimum max(R,G,B) for a pixel to be INCLUDED.\n"
                + "Pixels with max(R,G,B) below this value are EXCLUDED from analysis.\n"
                + "Excludes dark absorbing tissue such as hematoxylin-stained nuclei,\n"
                + "whose color comes from dye absorption rather than birefringence.\n\n"
                + "Example: a nucleus pixel of (60,50,80) has max=80 and would be\n"
                + "excluded at the default threshold of 100.\n\n"
                + "INCREASE -> more pixels EXCLUDED (cuts more dark tissue).\n"
                + "DECREASE -> more pixels INCLUDED (set to 0 to disable).\n"
                + "Default 100.");
        minIntTip.setShowDelay(Duration.millis(400));
        minIntensitySpinner.setTooltip(minIntTip);
        grid.add(minIntensitySpinner, 1, row);
        row++;

        Label hsvBlurLabel = new Label("Gaussian blur (px):");
        hsvBlurLabel.setStyle("-fx-padding: 0 0 0 16;");
        hsvBlurLabel.setTooltip(new Tooltip("Pre-blur applied to the RGB image before HSV saturation /\n"
                + "value / min-intensity thresholding (0-10 px). 0 = no blur."));
        grid.add(hsvBlurLabel, 0, row);
        Spinner<Double> hsvBlurSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 10, PPMPreferences.getHsvBlurSigma(), 0.5));
        hsvBlurSpinner.setEditable(true);
        hsvBlurSpinner.setTooltip(hsvBlurLabel.getTooltip());
        grid.add(hsvBlurSpinner, 1, row);
        row++;

        // -- Pixel classifier controls --
        Label classifierLabel = new Label("Classifier:");
        classifierLabel.setTooltip(new Tooltip("Pixel classifier or thresholder to define foreground mask"));
        grid.add(classifierLabel, 0, row);
        ChoiceBox<String> classifierChoice = new ChoiceBox<>();
        // Discover available pixel classifiers from the project
        List<String> classifierNames = new ArrayList<>();
        try {
            var classifierManager = project.getPixelClassifiers();
            classifierNames.addAll(classifierManager.getNames());
        } catch (Exception ex) {
            logger.debug("Could not list pixel classifiers: {}", ex.getMessage());
        }
        if (classifierNames.isEmpty()) {
            classifierNames.add("(none available)");
        }
        classifierChoice.getItems().addAll(classifierNames);
        String rememberedClassifier = PPMPreferences.getSelectedClassifier();
        if (rememberedClassifier != null
                && !rememberedClassifier.isEmpty()
                && classifierNames.contains(rememberedClassifier)) {
            classifierChoice.setValue(rememberedClassifier);
        } else {
            classifierChoice.setValue(classifierNames.get(0));
        }
        Tooltip classifierTip = new Tooltip("Select a pixel classifier or thresholder from the project.\n"
                + "The classifier's positive/foreground class will be used\n"
                + "as the analysis mask instead of the birefringence threshold.");
        classifierTip.setShowDelay(Duration.millis(400));
        classifierChoice.setTooltip(classifierTip);
        grid.add(classifierChoice, 1, row);
        row++;

        // Toggle enable/disable based on radio selection
        classifierLabel.setDisable(true);
        classifierChoice.setDisable(true);
        foregroundToggle.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            boolean useThresholds = newVal == thresholdRadio;
            birefLabel.setDisable(!useThresholds);
            birefSpinner.setDisable(!useThresholds);
            classifierLabel.setDisable(useThresholds);
            classifierChoice.setDisable(useThresholds);
        });

        // Pixel size display
        grid.add(new Label("Pixel size:"), 0, row);
        grid.add(new Label(String.format("%.4f um/px", pixelSizeUm)), 1, row);
        row++;

        // Paper reference
        Label refLabel = new Label("Based on PS-TACS: Qian et al., Am J Pathol 2025");
        refLabel.setStyle("-fx-text-fill: #4444aa; -fx-font-size: 10px;");
        grid.add(refLabel, 0, row, 3, 1);
        row++;

        // DOI link
        Label doiLabel = new Label("DOI: 10.1016/j.ajpath.2025.04.017");
        doiLabel.setStyle("-fx-text-fill: #4444aa; -fx-font-size: 10px; -fx-cursor: hand; -fx-underline: true;");
        doiLabel.setOnMouseClicked(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("https://doi.org/10.1016/j.ajpath.2025.04.017"));
            } catch (Exception ex) {
                logger.debug("Could not open DOI link: {}", ex.getMessage());
            }
        });
        grid.add(doiLabel, 0, row, 3, 1);
        row++;

        // Buttons
        Button previewButton = new Button("Preview mask...");
        previewButton.setTooltip(new Tooltip("Open a small live preview showing which pixels would be\n"
                + "excluded by the current thresholds (HSV/intensity or biref).\n"
                + "Updates as you change the spinner values."));
        previewButton.setOnAction(e -> {
            PPMAnalysisSet previewAnalysisSet =
                    currentEntry != null ? PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project) : null;
            PPMMaskPreviewWindow.show(
                    gui,
                    imageData,
                    previewAnalysisSet,
                    satSpinner,
                    valSpinner,
                    minIntensitySpinner,
                    birefSpinner,
                    hsvBlurSpinner,
                    birefBlurSpinner,
                    classifierRadio.selectedProperty());
        });
        Button runButton = new Button("Run Analysis");
        Button cancelButton = new Button("Cancel");
        HBox buttons = new HBox(10, previewButton, runButton, cancelButton);
        buttons.setPadding(new Insets(10, 0, 0, 0));
        grid.add(buttons, 0, row, 3, 1);

        cancelButton.setOnAction(e -> dialog.close());
        runButton.setOnAction(e -> {
            dialog.close();

            String selectedClass = classChoice.getValue();
            double dilationUm = dilationSpinner.getValue();
            String zoneMode = zoneChoice.getValue();
            double tacsThreshold = tacsSpinner.getValue();
            boolean fillHoles = fillHolesBox.isSelected();
            double birefThreshold = birefSpinner.getValue();
            double satThreshold = satSpinner.getValue();
            double valThreshold = valSpinner.getValue();
            boolean useClassifier = classifierRadio.isSelected();
            String selectedClassifier = classifierChoice.getValue();
            int minPolylineLength = minLengthSpinner.getValue();
            double boundarySigma = boundarySigmaSpinner.getValue();
            int smoothWindow = smoothWindowSpinner.getValue();
            int minCollagenArea = minAreaSpinner.getValue();
            double maskSigma = maskSigmaSpinner.getValue();
            int minRgbIntensity = minIntensitySpinner.getValue();
            boolean extendedTacs = extendedTacsBox.isSelected();
            double minCollagenDensity = densitySpinner.getValue();
            double minSignalThreshold = signalSpinner.getValue();
            double birefBlurSigma = birefBlurSpinner.getValue();
            double hsvBlurSigma = hsvBlurSpinner.getValue();
            boolean windowEnabled = windowEnabledCheck.isSelected();
            double windowSizeUmVal = windowSizeSpinner.getValue();
            int windowOverlapPctVal = windowOverlapSpinner.getValue();
            boolean windowCreateObjects = windowObjectsCheck.isSelected();

            // Persist user-modified values for next session
            PPMPreferences.setDilationUm(dilationUm);
            PPMPreferences.setTacsThresholdDeg(tacsThreshold);
            PPMPreferences.setBirefringenceThreshold(birefThreshold);
            PPMPreferences.setSaturationThreshold(satThreshold);
            PPMPreferences.setValueThreshold(valThreshold);
            PPMPreferences.setMinPolylineLengthPx(minPolylineLength);
            PPMPreferences.setMinRgbIntensity(minRgbIntensity);
            PPMPreferences.setBirefBlurSigma(birefBlurSigma);
            PPMPreferences.setHsvBlurSigma(hsvBlurSigma);
            PPMPreferences.setZoneMode(zoneMode);
            PPMPreferences.setFillHoles(fillHoles);
            PPMPreferences.setUseClassifier(useClassifier);
            PPMPreferences.setExtendedTACSEnabled(extendedTacs);
            PPMPreferences.setMinCollagenDensity(minCollagenDensity);
            PPMPreferences.setMinSignalThreshold(minSignalThreshold);
            PPMPreferences.setBoundarySmoothing(boundarySigma);
            PPMPreferences.setTACSContourSmoothing(smoothWindow);
            PPMPreferences.setMinCollagenArea(minCollagenArea);
            PPMPreferences.setMaskSmoothingSigma(maskSigma);
            PPMPreferences.setWindowAnalysisEnabled(windowEnabled);
            PPMPreferences.setWindowSizeUm(windowSizeUmVal);
            PPMPreferences.setWindowOverlapPercent(windowOverlapPctVal);
            PPMPreferences.setWindowCreateObjects(windowCreateObjects);
            if (useClassifier && selectedClassifier != null && !"(none available)".equals(selectedClassifier)) {
                PPMPreferences.setSelectedClassifier(selectedClassifier);
            }
            if (selectedClass != null) {
                PPMPreferences.setPerpBoundaryClass(selectedClass);
            }

            // Validate classifier selection
            if (useClassifier && ("(none available)".equals(selectedClassifier) || selectedClassifier == null)) {
                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        "No pixel classifier selected. Create a pixel classifier or thresholder\n"
                                + "in the QuPath project first, or switch to threshold mode.");
                return;
            }

            // Collect matching annotations
            List<PathObject> matchingAnnotations = allAnnotations.stream()
                    .filter(a -> a.getPathClass() != null
                            && a.getPathClass().toString().equals(selectedClass))
                    .collect(Collectors.toList());

            if (matchingAnnotations.isEmpty()) {
                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        DocumentationHelper.withDocLink(
                                "No annotations found with class '" + selectedClass + "'.", "ppmPerpendicularity"));
                return;
            }

            // Show results window
            ensureResultWindow(gui);

            // Find analysis set -- needed for biref-intensity mode and for classifier mode
            // (the classifier runs against the biref sibling, not the current/sum image).
            PPMAnalysisSet analysisSet = null;
            if (currentEntry != null) {
                analysisSet = PPMImageSetDiscovery.findPPMAnalysisSet(currentEntry, project);
            }

            // Load pixel classifier if selected (must be done on FX thread / before background).
            // In classifier mode the thresholder runs against the biref sibling image -- not the
            // current viewer image (which is typically the sum). Open the biref ImageData here so
            // we can validate channel compatibility before any annotation work starts.
            PixelClassifier pixelClassifier = null;
            ImageData<BufferedImage> classifierImageData = null;
            if (useClassifier) {
                try {
                    pixelClassifier = project.getPixelClassifiers().get(selectedClassifier);
                } catch (Exception ex) {
                    logger.error("Failed to load pixel classifier '{}': {}", selectedClassifier, ex.getMessage());
                    Dialogs.showErrorMessage(
                            "Surface Perpendicularity Analysis",
                            "Failed to load pixel classifier '" + selectedClassifier + "': " + ex.getMessage());
                    return;
                }

                if (analysisSet == null || !analysisSet.hasBirefImage()) {
                    Dialogs.showErrorMessage(
                            "Surface Perpendicularity Analysis",
                            "No biref sibling image found for the current entry.\n\n"
                                    + "The pixel classifier mode runs the thresholder against the\n"
                                    + "birefringence image (+7 / -7 PPM), so a biref sibling must be\n"
                                    + "present in the project for the current image.");
                    return;
                }

                try {
                    @SuppressWarnings("unchecked")
                    ImageData<BufferedImage> birefData =
                            (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
                    classifierImageData = birefData;
                } catch (Exception ex) {
                    logger.error(
                            "Failed to open biref sibling '{}': {}",
                            analysisSet.birefImage.getImageName(),
                            ex.getMessage());
                    Dialogs.showErrorMessage(
                            "Surface Perpendicularity Analysis",
                            "Failed to open biref sibling '" + analysisSet.birefImage.getImageName() + "': "
                                    + ex.getMessage());
                    return;
                }

                if (!pixelClassifier.supportsImage(classifierImageData)) {
                    StringBuilder chDump = new StringBuilder();
                    for (var ch : classifierImageData.getServer().getMetadata().getChannels()) {
                        String name = ch.getName();
                        int len = name == null ? 0 : name.length();
                        chDump.append("  - '")
                                .append(name)
                                .append("' (length=")
                                .append(len)
                                .append(")\n");
                    }
                    int nCh = classifierImageData.getServer().nChannels();
                    int expected = pixelClassifier.getMetadata().getInputNumChannels();
                    logger.error(
                            "Classifier '{}' does not support biref image '{}': expected {} channel(s), found {}. Channels: {}",
                            selectedClassifier,
                            analysisSet.birefImage.getImageName(),
                            expected,
                            nCh,
                            chDump);
                    try {
                        classifierImageData.getServer().close();
                    } catch (Exception ignore) {
                    }
                    Dialogs.showErrorMessage(
                            "Surface Perpendicularity Analysis",
                            "Classifier '" + selectedClassifier
                                    + "' does not support the biref image\n'"
                                    + analysisSet.birefImage.getImageName() + "'.\n\n"
                                    + "Expected " + expected + " input channel(s); biref image has "
                                    + nCh + ".\n\n"
                                    + "Biref image channel names (with literal lengths):\n"
                                    + chDump
                                    + "\nIf a name looks correct visually but the length is unexpected,\n"
                                    + "check for trailing whitespace / non-ASCII characters.\n"
                                    + "Otherwise rebuild the thresholder against this biref image.");
                    return;
                }
                logger.info(
                        "Classifier '{}' validated against biref image '{}'",
                        selectedClassifier,
                        analysisSet.birefImage.getImageName());
            }

            // Run in background
            final PPMAnalysisSet finalAnalysisSet = analysisSet;
            final PixelClassifier finalClassifier = pixelClassifier;
            final ImageData<BufferedImage> finalClassifierImageData = classifierImageData;
            final ImageServer<BufferedImage> sumServer = imageData.getServer();
            final PathObjectHierarchy hierarchy = imageData.getHierarchy();
            final int imageW = sumServer.getWidth();
            final int imageH = sumServer.getHeight();
            final int finalMinPolylineLength = minPolylineLength;

            // Build output dir
            Path projectDir = project.getPath().getParent();
            String imageName = currentEntry != null ? currentEntry.getImageName() : "unknown";
            Path outputDir = projectDir
                    .resolve("analysis")
                    .resolve("perpendicularity")
                    .resolve(imageName.replaceAll("[^a-zA-Z0-9._-]", "_"));
            if (resultPanel != null) {
                resultPanel.setAnalysisOutputDir(outputDir);
            }

            CompletableFuture.runAsync(() -> {
                try {
                    // Ensure Appose PPM environment is initialized
                    ensureEnvironmentReady();

                    // Remove previous perpendicularity results to avoid stacking
                    List<PathObject> previousResults = new ArrayList<>();
                    for (PathObject obj : hierarchy.getAllObjects(false)) {
                        PathClass pc = obj.getPathClass();
                        if (pc != null) {
                            String name = pc.toString();
                            if ("TACS-1".equals(name)
                                    || "TACS-2".equals(name)
                                    || "TACS-3".equals(name)
                                    || "PPM-Foreground".equals(name)
                                    || "PPM-Zone".equals(name)) {
                                previousResults.add(obj);
                            }
                        }
                    }
                    if (!previousResults.isEmpty()) {
                        hierarchy.removeObjects(previousResults, true);
                        logger.info("Removed {} previous perpendicularity result objects", previousResults.size());
                    }

                    List<PathObject> allTacsPolylines = new ArrayList<>();

                    for (int i = 0; i < matchingAnnotations.size(); i++) {
                        PathObject annotation = matchingAnnotations.get(i);
                        String annotationName = annotation.getDisplayedName();
                        int annotationIndex = i + 1;
                        int totalAnnotations = matchingAnnotations.size();

                        logger.info(
                                "Analyzing annotation {}/{}: '{}'", annotationIndex, totalAnnotations, annotationName);

                        Platform.runLater(() -> {
                            if (resultPanel != null) {
                                resultPanel.setStatus(String.format(
                                        "Analyzing %d/%d: %s...", annotationIndex, totalAnnotations, annotationName));
                            }
                        });

                        String safeAnnotationName =
                                annotationName == null ? "" : annotationName.replaceAll("[^a-zA-Z0-9._-]", "_");
                        String annotationDirName = safeAnnotationName.isEmpty()
                                ? String.format("annotation_%02d", annotationIndex)
                                : String.format("annotation_%02d_%s", annotationIndex, safeAnnotationName);
                        Path annotationOutputDir = outputDir.resolve(annotationDirName);

                        AnnotationResult annResult = computeForAnnotation(
                                sumServer,
                                imageData,
                                annotation.getROI(),
                                calibrationPath,
                                finalAnalysisSet,
                                pixelSizeUm,
                                dilationUm,
                                zoneMode,
                                tacsThreshold,
                                fillHoles,
                                birefThreshold,
                                satThreshold,
                                valThreshold,
                                finalClassifier,
                                finalClassifierImageData,
                                boundarySigma,
                                smoothWindow,
                                minCollagenArea,
                                maskSigma,
                                minRgbIntensity,
                                extendedTacs,
                                minCollagenDensity,
                                minSignalThreshold,
                                birefBlurSigma,
                                hsvBlurSigma,
                                windowEnabled,
                                windowSizeUmVal,
                                windowOverlapPctVal,
                                annotationOutputDir);

                        // Stamp identifying metadata onto the JSON so files saved in the
                        // analysis folder are self-describing (image + annotation names).
                        annResult.json.addProperty("image_name", imageName);
                        annResult.json.addProperty("annotation_name", annotationName);
                        annResult.json.addProperty("annotation_index", annotationIndex);

                        // Save JSON result
                        annotationOutputDir.toFile().mkdirs();
                        Path jsonPath = annotationOutputDir.resolve("results.json");
                        try (Writer writer = Files.newBufferedWriter(jsonPath)) {
                            new Gson().toJson(annResult.json, writer);
                        }

                        // Create interrogation-zone detection (ring between annotation boundary
                        // and dilation/erosion edge) so the user can see exactly what area was
                        // analysed.
                        double dilationPxForZone = dilationUm / pixelSizeUm;
                        PathObject zoneDetection =
                                createInterrogationZoneDetection(annotation.getROI(), zoneMode, dilationPxForZone);
                        if (zoneDetection != null) {
                            zoneDetection.getMeasurementList().put("Perp. parent", annotationIndex);
                            zoneDetection.getMeasurementList().put("Dilation (um)", dilationUm);
                            zoneDetection.setName("Zone: " + annotationName);
                            allTacsPolylines.add(zoneDetection);
                        }

                        // Save foreground mask as B/W PNG and create detection overlay
                        if (annResult.foregroundMask != null) {
                            saveForegroundMask(
                                    annResult.foregroundMask,
                                    annResult.regionW,
                                    annResult.regionH,
                                    annotationOutputDir);

                            PathObject maskDetection = createMaskDetection(
                                    annResult.foregroundMask,
                                    annResult.offsetX,
                                    annResult.offsetY,
                                    annResult.regionW,
                                    annResult.regionH);
                            if (maskDetection != null) {
                                maskDetection.getMeasurementList().put("Perp. parent", annotationIndex);
                                maskDetection.setName("Foreground: " + annotationName);
                                allTacsPolylines.add(maskDetection);
                            }
                        }

                        // Create TACS polyline annotations from contour data. When extended
                        // TACS is enabled, the polyline pass auto-consumes the extended class
                        // array so TACS-1 replaces (not overlays) TACS-2/3 on demoted segments.
                        List<PathObject> polylines = createTACSPolylines(
                                annResult.json,
                                annResult.offsetX,
                                annResult.offsetY,
                                imageW,
                                imageH,
                                finalMinPolylineLength);
                        for (PathObject polyline : polylines) {
                            polyline.getMeasurementList().put("Perp. parent", annotationIndex);
                        }
                        allTacsPolylines.addAll(polylines);

                        logger.info("Created {} TACS polylines for annotation '{}'", polylines.size(), annotationName);

                        // Optional: per-window detection objects from windows.json.
                        if (windowCreateObjects) {
                            Path windowsJson = annotationOutputDir.resolve("windows.json");
                            if (Files.exists(windowsJson)) {
                                List<PathObject> windowObjects = createWindowDetections(
                                        windowsJson, annResult.offsetX, annResult.offsetY, annotationIndex);
                                allTacsPolylines.addAll(windowObjects);
                                logger.info(
                                        "Created {} window detection objects for annotation '{}'",
                                        windowObjects.size(),
                                        annotationName);
                            } else {
                                logger.info(
                                        "Window-create requested but windows.json missing for annotation '{}'",
                                        annotationName);
                            }
                        }

                        // Display results
                        final JsonObject finalResult = annResult.json;
                        final String finalName = annotationName;
                        final int idx = annotationIndex;
                        final int total = totalAnnotations;
                        final byte[] maskBytesForUI = annResult.foregroundMask;
                        final int maskUIW = annResult.regionW;
                        final int maskUIH = annResult.regionH;
                        final int overlayOffsetX = annResult.offsetX;
                        final int overlayOffsetY = annResult.offsetY;
                        Path devPng = annotationOutputDir.resolve("deviation_overlay.png");
                        Path alignPng = annotationOutputDir.resolve("alignment_overlay.png");
                        Path winOrientPng = annotationOutputDir.resolve("orientation_overlay.png");
                        final Path devPngFinal = Files.exists(devPng) ? devPng : null;
                        final Path alignPngFinal = Files.exists(alignPng) ? alignPng : null;
                        final Path winOrientPngFinal = Files.exists(winOrientPng) ? winOrientPng : null;
                        final PPMPerpendicularityPanel.OverlayInfo overlayInfo =
                                (devPngFinal != null || alignPngFinal != null || winOrientPngFinal != null)
                                        ? new PPMPerpendicularityPanel.OverlayInfo(
                                                devPngFinal,
                                                alignPngFinal,
                                                winOrientPngFinal,
                                                overlayOffsetX,
                                                overlayOffsetY,
                                                maskUIW,
                                                maskUIH)
                                        : null;
                        Platform.runLater(() -> {
                            if (resultPanel != null) {
                                WritableImage maskFXImage = null;
                                if (maskBytesForUI != null) {
                                    maskFXImage = new WritableImage(maskUIW, maskUIH);
                                    PixelWriter pw = maskFXImage.getPixelWriter();
                                    for (int my = 0; my < maskUIH; my++) {
                                        for (int mx = 0; mx < maskUIW; mx++) {
                                            int val = maskBytesForUI[my * maskUIW + mx] & 0xFF;
                                            pw.setArgb(mx, my, 0xFF000000 | (val << 16) | (val << 8) | val);
                                        }
                                    }
                                }
                                resultPanel.addResult(finalResult, finalName, idx, total, maskFXImage, overlayInfo);
                            }
                        });
                    }

                    // Add all TACS polylines to hierarchy at once
                    if (!allTacsPolylines.isEmpty()) {
                        hierarchy.addObjects(allTacsPolylines);
                        logger.info("Added {} TACS polyline annotations total", allTacsPolylines.size());
                    }

                    Platform.runLater(() -> {
                        if (resultPanel != null) {
                            resultPanel.setStatus("Analysis complete.\nResults saved to: " + outputDir);
                        }
                        if (resultWindow != null) {
                            resultWindow.show();
                            resultWindow.toFront();
                        }
                    });

                    logger.info("Perpendicularity analysis complete. Results: {}", outputDir);

                } catch (Exception ex) {
                    logger.error("Perpendicularity analysis failed", ex);
                    Platform.runLater(() -> Dialogs.showErrorMessage(
                            "Surface Perpendicularity Analysis",
                            DocumentationHelper.withDocLink(
                                    "Analysis failed: " + ex.getMessage(), "ppmPerpendicularity")));
                } finally {
                    if (finalClassifierImageData != null) {
                        try {
                            finalClassifierImageData.getServer().close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            });
        });

        VBox dialogRoot = new VBox(grid);
        if (helpButton != null) {
            HBox helpBar = new HBox(helpButton);
            helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            helpBar.setPadding(new Insets(5, 10, 0, 10));
            dialogRoot.getChildren().add(0, helpBar);
        }

        ScrollPane scrollPane = new ScrollPane(dialogRoot);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");

        dialog.setScene(new Scene(scrollPane, 550, 650));
        dialog.setResizable(true);
        dialog.show();
    }

    private static AnnotationResult computeForAnnotation(
            ImageServer<BufferedImage> sumServer,
            ImageData<BufferedImage> imageData,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            double pixelSizeUm,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles,
            double birefThreshold,
            double saturationThreshold,
            double valueThreshold,
            PixelClassifier classifier,
            ImageData<BufferedImage> classifierImageData,
            double boundarySmoothingSigma,
            int smoothingWindow,
            int minCollagenArea,
            double maskSmoothingSigma,
            int minRgbIntensity,
            boolean extendedTacs,
            double minCollagenDensity,
            double minSignalThreshold,
            double birefBlurSigma,
            double hsvBlurSigma,
            boolean windowAnalysisEnabled,
            double windowSizeUm,
            int windowOverlapPercent,
            Path outputDir)
            throws Exception {

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        // Expand region to include dilation zone
        double dilationPx = dilationUm / pixelSizeUm;
        int pad = (int) Math.ceil(dilationPx) + 5;
        int expandedX = Math.max(0, x - pad);
        int expandedY = Math.max(0, y - pad);
        int expandedW = Math.min(sumServer.getWidth() - expandedX, w + 2 * pad);
        int expandedH = Math.min(sumServer.getHeight() - expandedY, h + 2 * pad);

        logger.info(
                "Region: {}x{} at ({},{}) padded to {}x{} at ({},{})",
                w,
                h,
                x,
                y,
                expandedW,
                expandedH,
                expandedX,
                expandedY);

        RegionRequest request =
                RegionRequest.createInstance(sumServer.getPath(), 1.0, expandedX, expandedY, expandedW, expandedH);

        // Read sum region and convert to NDArray
        BufferedImage sumRegion = sumServer.readRegion(request);
        NDArray sumNDArray = null;
        NDArray birefNDArray = null;
        NDArray foregroundNDArray = null;

        try {
            sumNDArray = bufferedImageToRGBNDArray(sumRegion);

            if (classifier != null) {
                // Pixel classifier mode: thresholder runs against the biref sibling ImageData,
                // not the current/sum viewer image. classifierImageData is opened once in the
                // outer workflow scope and closed there after the loop completes.
                ImageData<BufferedImage> clsData = classifierImageData != null ? classifierImageData : imageData;
                BufferedImage maskImage =
                        generateClassifierMaskImage(clsData, classifier, expandedX, expandedY, expandedW, expandedH);
                foregroundNDArray = bufferedImageToGrayNDArray(maskImage);
            } else if (analysisSet != null && analysisSet.hasBirefImage()) {
                try {
                    @SuppressWarnings("unchecked")
                    ImageData<BufferedImage> birefData =
                            (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
                    ImageServer<BufferedImage> birefServer = birefData.getServer();
                    RegionRequest birefRequest = RegionRequest.createInstance(
                            birefServer.getPath(), 1.0, expandedX, expandedY, expandedW, expandedH);
                    BufferedImage birefRegion = birefServer.readRegion(birefRequest);
                    birefNDArray = bufferedImageToGray16NDArray(birefRegion);
                    birefServer.close();
                } catch (Exception e) {
                    logger.warn("Could not read biref sibling: {}", e.getMessage());
                }
            }

            // Export ROI as GeoJSON string (coordinates relative to expanded region)
            String geojsonString = exportRoiAsGeoJSONString(roi, expandedX, expandedY);

            // Build Appose task inputs
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("sum_image", sumNDArray);
            inputs.put("calibration_path", calibrationPath);
            inputs.put("geojson_boundary", geojsonString);
            inputs.put("pixel_size_um", pixelSizeUm);
            inputs.put("dilation_um", dilationUm);
            inputs.put("zone_mode", zoneMode);
            inputs.put("tacs_threshold", tacsThreshold);
            inputs.put("fill_holes", fillHoles);
            inputs.put("saturation_threshold", saturationThreshold);
            inputs.put("value_threshold", valueThreshold);
            inputs.put("image_width", expandedW);
            inputs.put("image_height", expandedH);
            inputs.put("boundary_smoothing_sigma", boundarySmoothingSigma);
            inputs.put("smoothing_window", smoothingWindow);
            inputs.put("min_collagen_area", minCollagenArea);
            inputs.put("mask_smoothing_sigma", maskSmoothingSigma);
            inputs.put("min_rgb_intensity", minRgbIntensity);
            inputs.put("biref_blur_sigma", birefBlurSigma);
            inputs.put("hsv_blur_sigma", hsvBlurSigma);
            inputs.put("window_analysis_enabled", windowAnalysisEnabled);
            inputs.put("window_size_um", windowSizeUm);
            inputs.put("window_overlap_percent", windowOverlapPercent);
            inputs.put("extended_tacs", extendedTacs);
            inputs.put("min_collagen_density", minCollagenDensity);
            inputs.put("min_signal_threshold", minSignalThreshold);

            if (birefNDArray != null) {
                inputs.put("biref_image", birefNDArray);
                inputs.put("biref_threshold", birefThreshold);
            }
            if (foregroundNDArray != null) {
                inputs.put("foreground_mask", foregroundNDArray);
            }
            if (outputDir != null) {
                inputs.put("output_dir", outputDir.toString());
            }

            logger.info("Running perpendicularity via Appose ({} inputs)", inputs.size());

            Task task = ApposePPMService.getInstance().runTask("run_perpendicularity", inputs);
            String json = (String) task.outputs.get("result_json");

            Gson gson = new Gson();
            JsonObject result = gson.fromJson(json, JsonObject.class);

            if (result.has("error") && !result.get("error").isJsonNull()) {
                throw new RuntimeException(
                        "Python error: " + result.get("error").getAsString());
            }

            // Extract foreground mask NDArray if returned by Python
            byte[] maskBytes = null;
            NDArray maskNDArray = null;
            try {
                if (task.outputs.containsKey("foreground_mask")) {
                    maskNDArray = (NDArray) task.outputs.get("foreground_mask");
                    maskBytes = new byte[expandedW * expandedH];
                    maskNDArray.buffer().get(maskBytes);
                    logger.info("Received foreground mask: {}x{}", expandedW, expandedH);
                }
            } catch (Exception e) {
                logger.warn("Could not read foreground mask from output: {}", e.getMessage());
            } finally {
                if (maskNDArray != null) maskNDArray.close();
            }

            return new AnnotationResult(result, expandedX, expandedY, expandedW, expandedH, maskBytes);

        } finally {
            if (sumNDArray != null) sumNDArray.close();
            if (birefNDArray != null) birefNDArray.close();
            if (foregroundNDArray != null) foregroundNDArray.close();
        }
    }

    /**
     * Applies a pixel classifier to a region and returns a binary mask BufferedImage
     * (255 = foreground, 0 = background).
     */
    private static BufferedImage generateClassifierMaskImage(
            ImageData<BufferedImage> imageData, PixelClassifier classifier, int x, int y, int w, int h)
            throws Exception {

        try (ImageServer<BufferedImage> classServer =
                PixelClassifierTools.createPixelClassificationServer(imageData, classifier)) {

            RegionRequest classRequest = RegionRequest.createInstance(classServer.getPath(), 1.0, x, y, w, h);
            BufferedImage classImage = classServer.readRegion(classRequest);

            int maskW = classImage.getWidth();
            int maskH = classImage.getHeight();
            BufferedImage mask = new BufferedImage(maskW, maskH, BufferedImage.TYPE_BYTE_GRAY);
            Raster classRaster = classImage.getRaster();

            int fgCount = 0;
            for (int py = 0; py < maskH; py++) {
                for (int px = 0; px < maskW; px++) {
                    int classIndex = classRaster.getSample(px, py, 0);
                    if (classIndex > 0) {
                        mask.getRaster().setSample(px, py, 0, 255);
                        fgCount++;
                    }
                }
            }
            logger.info(
                    "Classifier mask: {}x{}, {} foreground pixels ({} pct)",
                    maskW,
                    maskH,
                    fgCount,
                    maskW * maskH > 0 ? String.format("%.1f", 100.0 * fgCount / (maskW * maskH)) : "0");

            return mask;
        }
    }

    /**
     * A contiguous run of contour points with the same TACS classification.
     */
    private static class TACSSegment {
        int tacsClass;
        final List<Double> xCoords = new ArrayList<>();
        final List<Double> yCoords = new ArrayList<>();

        TACSSegment(int tacsClass) {
            this.tacsClass = tacsClass;
        }

        void add(double x, double y) {
            xCoords.add(x);
            yCoords.add(y);
        }

        int size() {
            return xCoords.size();
        }

        void absorb(TACSSegment other) {
            xCoords.addAll(other.xCoords);
            yCoords.addAll(other.yCoords);
        }
    }

    /**
     * Creates TACS-2 and TACS-3 Polyline annotations from per-contour-pixel classification.
     *
     * <p>Uses a two-pass approach: first collects contiguous runs of the same TACS class,
     * then merges short segments into adjacent same-class neighbors before creating
     * polylines. This prevents gaps where short noisy segments are filtered out.</p>
     *
     * <p>Contour points are translated from cropped-region coordinates to full-image
     * coordinates. Points on the image border are excluded.</p>
     *
     * @param result JSON result from Python analysis
     * @param offsetX X offset of cropped region in full image
     * @param offsetY Y offset of cropped region in full image
     * @param imageW full image width (for border filtering)
     * @param imageH full image height (for border filtering)
     * @param minSegmentLength minimum number of points for a polyline segment
     * @return list of Polyline PathObjects (may be empty)
     */
    private static List<PathObject> createTACSPolylines(
            JsonObject result, int offsetX, int offsetY, int imageW, int imageH, int minSegmentLength) {

        List<PathObject> polylines = new ArrayList<>();

        // Prefer extended TACS classes (0=Unclassified, 1=TACS-1, 2=TACS-2, 3=TACS-3)
        // when present. Class 0 acts as a hard break so no polyline is drawn through
        // Unclassified stretches, and TACS-1 actually replaces the underlying TACS-2/3
        // for demoted segments (not just overlaid on top).
        JsonObject extTacs =
                result.has("extended_tacs") && !result.get("extended_tacs").isJsonNull()
                        ? result.getAsJsonObject("extended_tacs")
                        : null;
        JsonObject pstacs =
                result.has("pstacs") && !result.get("pstacs").isJsonNull() ? result.getAsJsonObject("pstacs") : null;
        JsonObject source = extTacs != null ? extTacs : pstacs;
        if (source == null) return polylines;

        String classKey = extTacs != null ? "extended_tacs_class" : "contour_tacs_class";
        JsonArray pointsArr = source.has("contour_points") ? source.getAsJsonArray("contour_points") : null;
        JsonArray classArr = source.has(classKey) ? source.getAsJsonArray(classKey) : null;
        if (pointsArr == null || classArr == null || pointsArr.size() == 0) return polylines;

        int n = Math.min(pointsArr.size(), classArr.size());

        PathClass tacs1Class = PathClass.fromString("TACS-1", TACS1_COLOR);
        tacs1Class.setColor(TACS1_COLOR);
        PathClass tacs2Class = PathClass.fromString("TACS-2", TACS2_COLOR);
        tacs2Class.setColor(TACS2_COLOR);
        PathClass tacs3Class = PathClass.fromString("TACS-3", TACS3_COLOR);
        tacs3Class.setColor(TACS3_COLOR);

        double margin = IMAGE_BORDER_MARGIN_PX;

        // Pass 1: contiguous same-class runs. Class 0 (Unclassified) and image-border
        // points are hard breaks; a null separator prevents merging across the gap.
        List<TACSSegment> segments = new ArrayList<>();
        TACSSegment current = null;

        for (int i = 0; i < n; i++) {
            int tacsClass = classArr.get(i).getAsInt();
            if (tacsClass == 0) {
                if (current != null && current.size() > 0) {
                    segments.add(current);
                    current = null;
                }
                if (!segments.isEmpty() && segments.get(segments.size() - 1) != null) {
                    segments.add(null);
                }
                continue;
            }

            JsonArray pt = pointsArr.get(i).getAsJsonArray();
            double px = pt.get(0).getAsDouble() + offsetX;
            double py = pt.get(1).getAsDouble() + offsetY;

            if (px < margin || py < margin || px > imageW - margin || py > imageH - margin) {
                if (current != null && current.size() > 0) {
                    segments.add(current);
                    current = null;
                }
                if (!segments.isEmpty() && segments.get(segments.size() - 1) != null) {
                    segments.add(null);
                }
                continue;
            }

            if (current == null || tacsClass != current.tacsClass) {
                if (current != null && current.size() > 0) {
                    segments.add(current);
                }
                current = new TACSSegment(tacsClass);
            }

            current.add(px, py);
        }
        if (current != null && current.size() > 0) {
            segments.add(current);
        }

        // Pass 2: absorb short segments into adjacent same-class neighbours.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < segments.size() - 1; i++) {
                TACSSegment seg = segments.get(i);
                if (seg == null) continue;
                if (seg.size() >= minSegmentLength) continue;

                TACSSegment prev = segments.get(i - 1);
                TACSSegment next = segments.get(i + 1);
                if (prev == null || next == null) continue;

                if (prev.tacsClass == next.tacsClass) {
                    prev.absorb(seg);
                    prev.absorb(next);
                    segments.remove(i + 1);
                    segments.remove(i);
                    changed = true;
                    break;
                }
            }
        }

        // Pass 3: build polylines.
        for (TACSSegment seg : segments) {
            if (seg == null) continue;
            if (seg.size() < minSegmentLength) continue;
            PathClass pathClass;
            switch (seg.tacsClass) {
                case 1 -> pathClass = tacs1Class;
                case 3 -> pathClass = tacs3Class;
                default -> pathClass = tacs2Class;
            }
            PathObject polyline = buildPolyline(seg.xCoords, seg.yCoords, pathClass);
            if (polyline != null) polylines.add(polyline);
        }

        return polylines;
    }

    /**
     * Builds a Polyline annotation from accumulated point lists.
     * Returns null if fewer than 2 points (can't form a line).
     */
    private static PathObject buildPolyline(List<Double> xList, List<Double> yList, PathClass pathClass) {
        int size = xList.size();
        if (size < 2) return null;

        double[] xs = new double[size];
        double[] ys = new double[size];
        for (int i = 0; i < size; i++) {
            xs[i] = xList.get(i);
            ys[i] = yList.get(i);
        }

        ROI polylineRoi = ROIs.createPolylineROI(xs, ys, ImagePlane.getDefaultPlane());
        return PathObjects.createAnnotationObject(polylineRoi, pathClass);
    }

    /**
     * Exports an ROI as a GeoJSON Feature with coordinates adjusted to be
     * relative to an offset (expanded region origin).
     */
    static void exportRoiAsGeoJSON(ROI roi, int offsetX, int offsetY, Path outputPath) throws Exception {
        // Use QuPath's built-in GeoJSON serialization for the ROI geometry
        Gson gson = GsonTools.getInstance();

        // Create a PathObject wrapper for the ROI to use QuPath's serialization
        PathObject tempObj = qupath.lib.objects.PathObjects.createAnnotationObject(roi);
        String json = gson.toJson(tempObj);
        JsonObject feature = new Gson().fromJson(json, JsonObject.class);

        // Adjust coordinates by subtracting the offset
        JsonObject geometry = feature.getAsJsonObject("geometry");
        if (geometry != null) {
            adjustGeoJSONCoordinates(geometry, -offsetX, -offsetY);
        }

        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            new Gson().toJson(feature, writer);
        }
    }

    /**
     * Recursively adjusts all coordinate values in a GeoJSON geometry object.
     */
    private static void adjustGeoJSONCoordinates(JsonElement element, double dx, double dy) {
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            if (arr.size() >= 2
                    && arr.get(0).isJsonPrimitive()
                    && arr.get(0).getAsJsonPrimitive().isNumber()) {
                // This is a coordinate pair [x, y]
                double x = arr.get(0).getAsDouble() + dx;
                double y = arr.get(1).getAsDouble() + dy;
                arr.set(0, new com.google.gson.JsonPrimitive(x));
                arr.set(1, new com.google.gson.JsonPrimitive(y));
            } else {
                // Array of coordinates or rings
                for (int i = 0; i < arr.size(); i++) {
                    adjustGeoJSONCoordinates(arr.get(i), dx, dy);
                }
            }
        } else if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("coordinates")) {
                adjustGeoJSONCoordinates(obj.get("coordinates"), dx, dy);
            }
            if (obj.has("geometries")) {
                adjustGeoJSONCoordinates(obj.get("geometries"), dx, dy);
            }
        }
    }

    /**
     * Converts an RGB BufferedImage to an Appose NDArray (H, W, 3) uint8.
     */
    static NDArray bufferedImageToRGBNDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w, 3);
        NDArray ndArray = new NDArray(NDArray.DType.UINT8, shape);
        java.nio.ByteBuffer buf = ndArray.buffer();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = img.getRGB(x, y);
                buf.put((byte) ((pixel >> 16) & 0xFF));
                buf.put((byte) ((pixel >> 8) & 0xFF));
                buf.put((byte) (pixel & 0xFF));
            }
        }
        buf.flip();
        return ndArray;
    }

    /**
     * Converts a grayscale BufferedImage to an Appose NDArray (H, W) uint8.
     * Suitable for binary masks and 8-bit grayscale images.
     */
    static NDArray bufferedImageToGrayNDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w);
        NDArray ndArray = new NDArray(NDArray.DType.UINT8, shape);
        java.nio.ByteBuffer buf = ndArray.buffer();
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.put((byte) raster.getSample(x, y, 0));
            }
        }
        buf.flip();
        return ndArray;
    }

    /**
     * Converts a grayscale BufferedImage to an Appose NDArray (H, W) uint16.
     * Required for 16-bit images such as birefringence data where values exceed 255.
     */
    static NDArray bufferedImageToGray16NDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w);
        NDArray ndArray = new NDArray(NDArray.DType.UINT16, shape);
        java.nio.ShortBuffer buf =
                ndArray.buffer().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.put((short) raster.getSample(x, y, 0));
            }
        }
        return ndArray;
    }

    /**
     * Exports an ROI as a GeoJSON string with coordinates adjusted relative to an offset.
     */
    static String exportRoiAsGeoJSONString(ROI roi, int offsetX, int offsetY) {
        Gson gsonTools = GsonTools.getInstance();
        PathObject tempObj = PathObjects.createAnnotationObject(roi);
        String json = gsonTools.toJson(tempObj);
        JsonObject feature = new Gson().fromJson(json, JsonObject.class);

        JsonObject geometry = feature.getAsJsonObject("geometry");
        if (geometry != null) {
            adjustGeoJSONCoordinates(geometry, -offsetX, -offsetY);
        }

        return new Gson().toJson(feature);
    }

    /**
     * Ensures the Appose PPM environment is ready, initializing if needed.
     * Shows a progress dialog on first use.
     */
    private static void ensureEnvironmentReady() throws IOException {
        ApposePPMService service = ApposePPMService.getInstance();
        if (!service.isAvailable()) {
            logger.info("PPM analysis environment not ready, initializing...");
            service.initialize(msg -> logger.info("PPM setup: {}", msg));
        }
        if (!service.isVersionCompatible()) {
            throw new IOException("ppm_library " + service.getInstalledPpmVersion()
                    + " is outdated (need " + ApposePPMService.getRequiredPpmVersion()
                    + "+). Delete the environment at "
                    + ApposePPMService.getEnvironmentPath()
                    + " and restart QuPath to rebuild.");
        }
    }

    private static String findCalibrationPath(ProjectImageEntry<BufferedImage> entry, Project<BufferedImage> project) {
        PPMAnalysisSet analysisSet = null;
        if (entry != null && project != null) {
            analysisSet = PPMImageSetDiscovery.findPPMAnalysisSet(entry, project);
        }

        String calibrationPath = null;
        if (analysisSet != null && analysisSet.hasCalibration()) {
            calibrationPath = analysisSet.calibrationPath;
        }
        if (calibrationPath == null && entry != null) {
            calibrationPath = ImageMetadataManager.getPPMCalibration(entry);
        }
        if (calibrationPath == null) {
            String activePath = qupath.ext.ppm.PPMPreferences.getActiveCalibrationPath();
            if (activePath != null && !activePath.isEmpty()) {
                calibrationPath = activePath;
            }
        }
        return calibrationPath;
    }

    private static void ensureResultWindow(QuPathGUI gui) {
        if (resultWindow == null || !resultWindow.isShowing()) {
            resultPanel = new PPMPerpendicularityPanel();
            Scene scene = new Scene(resultPanel, 550, 600);
            resultWindow = new Stage();
            resultWindow.setTitle("Surface Perpendicularity Analysis");
            resultWindow.setScene(scene);
            resultWindow.initOwner(gui.getStage());
        } else {
            resultPanel.clear();
        }
        resultWindow.show();
        resultWindow.toFront();
    }

    /**
     * Saves the foreground mask as a B/W PNG in the output directory.
     */
    private static void saveForegroundMask(byte[] maskBytes, int width, int height, Path outputDir) {
        try {
            BufferedImage maskImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            maskImage.getRaster().setDataElements(0, 0, width, height, maskBytes);
            outputDir.toFile().mkdirs();
            Path maskPath = outputDir.resolve("foreground_mask.png");
            ImageIO.write(maskImage, "PNG", maskPath.toFile());
            logger.info("Saved foreground mask to {}", maskPath);
        } catch (Exception e) {
            logger.warn("Failed to save foreground mask: {}", e.getMessage());
        }
    }

    /**
     * Creates a detection from a foreground mask using contour tracing.
     * The mask is converted to a BufferedImage, contours are traced to create
     * actual geometry matching the thresholded pixels, and a detection is
     * created with the "PPM-Foreground" class.
     */
    /**
     * Build a detection covering the interrogation zone for an annotation: the ring between
     * the annotation boundary and the dilation/erosion edge implied by zoneMode.
     *
     * <p>outside: ring from boundary outward by dilationPx (buffer(+d) minus original).<br>
     * inside: ring from boundary inward by dilationPx (original minus buffer(-d)).<br>
     * both: full ring (buffer(+d) minus buffer(-d)).</p>
     */
    private static PathObject createInterrogationZoneDetection(ROI annotationRoi, String zoneMode, double dilationPx) {
        if (annotationRoi == null || dilationPx <= 0) return null;
        try {
            org.locationtech.jts.geom.Geometry base = annotationRoi.getGeometry();
            if (base == null || base.isEmpty()) return null;

            org.locationtech.jts.geom.Geometry ringGeom;
            String mode = zoneMode == null ? "outside" : zoneMode.toLowerCase();
            switch (mode) {
                case "inside" -> {
                    org.locationtech.jts.geom.Geometry inner = base.buffer(-dilationPx);
                    ringGeom = (inner == null || inner.isEmpty()) ? base : base.difference(inner);
                }
                case "both" -> {
                    org.locationtech.jts.geom.Geometry outer = base.buffer(dilationPx);
                    org.locationtech.jts.geom.Geometry inner = base.buffer(-dilationPx);
                    ringGeom = (inner == null || inner.isEmpty()) ? outer : outer.difference(inner);
                }
                default -> {
                    org.locationtech.jts.geom.Geometry outer = base.buffer(dilationPx);
                    ringGeom = outer.difference(base);
                }
            }
            if (ringGeom == null || ringGeom.isEmpty()) return null;

            ROI ringRoi = GeometryTools.geometryToROI(ringGeom, annotationRoi.getImagePlane());
            int zoneColor = ColorTools.packRGB(200, 200, 80);
            PathClass zoneClass = PathClass.fromString("PPM-Zone", zoneColor);
            zoneClass.setColor(zoneColor);
            PathObject detection = PathObjects.createDetectionObject(ringRoi, zoneClass);
            detection.getMeasurementList().put("Zone area (px)", ringGeom.getArea());
            detection.getMeasurementList().put("Zone mode", mode.equals("inside") ? 0 : mode.equals("both") ? 1 : 2);
            return detection;
        } catch (Exception ex) {
            logger.warn("Failed to build interrogation-zone detection: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Build one rectangular detection per non-empty window described in the
     * given {@code windows.json}. Each detection's ROI is anchored at the
     * window's pixel coordinates (translated by the region offset), and the
     * mean angle, order parameter, and valid-pixel count are attached as
     * measurements so they show up in QuPath's measurement table.
     */
    private static List<PathObject> createWindowDetections(
            Path windowsJsonPath, int offsetX, int offsetY, int annotationIndex) {
        List<PathObject> out = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(windowsJsonPath)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            if (root == null || !root.has("windows")) {
                return out;
            }
            JsonArray windows = root.getAsJsonArray("windows");
            int outlineColor = ColorTools.packRGB(140, 140, 200);
            PathClass windowClass = PathClass.fromString("PPM-Window", outlineColor);
            windowClass.setColor(outlineColor);
            for (JsonElement el : windows) {
                if (!el.isJsonObject()) continue;
                JsonObject w = el.getAsJsonObject();
                int wx = w.get("x").getAsInt() + offsetX;
                int wy = w.get("y").getAsInt() + offsetY;
                int ww = w.get("w").getAsInt();
                int wh = w.get("h").getAsInt();
                double meanAngle = w.get("mean_angle_deg").getAsDouble();
                double orderParam = w.get("order_parameter").getAsDouble();
                int nPx = w.get("n_pixels").getAsInt();

                ROI rect = ROIs.createRectangleROI(wx, wy, ww, wh, ImagePlane.getDefaultPlane());
                PathObject det = PathObjects.createDetectionObject(rect, windowClass);
                det.getMeasurementList().put("Perp. parent", annotationIndex);
                det.getMeasurementList().put("Window mean angle (deg)", meanAngle);
                det.getMeasurementList().put("Window order parameter", orderParam);
                det.getMeasurementList().put("Window valid pixels", nPx);
                out.add(det);
            }
        } catch (Exception ex) {
            logger.warn("Failed to parse windows.json {}: {}", windowsJsonPath, ex.getMessage());
        }
        return out;
    }

    private static PathObject createMaskDetection(byte[] maskBytes, int offsetX, int offsetY, int width, int height) {
        try {
            // Build a BufferedImage from the mask bytes
            BufferedImage maskImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            maskImage.getRaster().setDataElements(0, 0, width, height, maskBytes);

            // Create a RegionRequest for coordinate translation
            // The mask covers the expanded region starting at (offsetX, offsetY)
            RegionRequest request = RegionRequest.createInstance("mask", 1.0, offsetX, offsetY, width, height);

            // Trace contours from the binary mask (0/255 values)
            org.locationtech.jts.geom.Geometry geometry =
                    ContourTracing.createTracedGeometry(maskImage.getRaster(), 127.5, 255.5, 0, request);

            if (geometry == null || geometry.isEmpty()) {
                logger.info("Foreground mask is empty after contour tracing");
                return null;
            }

            ROI maskRoi = GeometryTools.geometryToROI(geometry, ImagePlane.getDefaultPlane());
            PathClass foregroundClass = PathClass.fromString("PPM-Foreground", ColorTools.packRGB(0, 255, 255));
            foregroundClass.setColor(ColorTools.packRGB(0, 255, 255));
            PathObject detection = PathObjects.createDetectionObject(maskRoi, foregroundClass);

            // Count foreground pixels for measurement
            int fgCount = 0;
            for (byte b : maskBytes) {
                if ((b & 0xFF) > 0) fgCount++;
            }
            int totalPixels = width * height;
            double fgPercent = totalPixels > 0 ? 100.0 * fgCount / totalPixels : 0;

            detection.getMeasurementList().put("Foreground pixels", fgCount);
            detection.getMeasurementList().put("Total pixels", totalPixels);
            detection.getMeasurementList().put("Foreground %", fgPercent);

            logger.info(
                    "Created foreground detection: {} fg pixels / {} total ({} pct)",
                    fgCount,
                    totalPixels,
                    String.format("%.1f", fgPercent));

            return detection;
        } catch (Exception e) {
            logger.warn("Failed to create mask detection: {}", e.getMessage());
            return null;
        }
    }
}
