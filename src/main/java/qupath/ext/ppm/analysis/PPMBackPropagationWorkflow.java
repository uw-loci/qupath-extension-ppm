package qupath.ext.ppm.analysis;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * PPM-specific annotation propagation using QPSC's propagation infrastructure.
 *
 * <p>Uses {@link ForwardPropagationWorkflow} for coordinate transforms (including
 * half-FOV correction, flip handling) while adding PPM-specific features:
 * <ul>
 *   <li>Collection-based grouping (image_collection metadata)</li>
 *   <li>Annotation naming with region prefix</li>
 *   <li>Measurement propagation option</li>
 *   <li>Annotation locking option</li>
 *   <li>Detailed reproducibility logging</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMBackPropagationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMBackPropagationWorkflow.class);

    /**
     * Groups entries by image collection for back-propagation.
     */
    static class CollectionGroup {
        int collection;
        String sampleName;
        ProjectImageEntry<BufferedImage> flippedParent;
        ProjectImageEntry<BufferedImage> originalBase;
        List<ProjectImageEntry<BufferedImage>> subImages = new ArrayList<>();
    }

    /**
     * Main entry point.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run back-propagation workflow", e);
                Dialogs.showErrorMessage(
                        "Back-Propagation",
                        DocumentationHelper.withDocLink("Error: " + e.getMessage(), "ppmBackPropagate"));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void runOnFXThread() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null) {
            Dialogs.showErrorMessage(
                    "Back-Propagation",
                    DocumentationHelper.withDocLink("QuPath GUI not available.", "ppmBackPropagate"));
            return;
        }

        Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Back-Propagation", DocumentationHelper.withDocLink("No project is open.", "ppmBackPropagate"));
            return;
        }

        // Step 1: Discover collections with sub-images and parent images
        List<CollectionGroup> collections = discoverCollections(project);
        if (collections.isEmpty()) {
            Dialogs.showInfoNotification(
                    "Back-Propagation", "No image collections with sub-images and parent images found.");
            return;
        }

        // Step 2: Collect annotation classes from all sub-images
        List<String> allClasses = collectAnnotationClasses(collections);
        if (allClasses.isEmpty()) {
            Dialogs.showInfoNotification("Back-Propagation", "No classified annotations found on sub-images.");
            return;
        }

        // Step 3: Show configuration dialog
        ToggleGroup targetGroup = new ToggleGroup();
        RadioButton toOriginalRadio = new RadioButton("Original base image (default)");
        RadioButton toFlippedRadio = new RadioButton("Flipped XY parent");
        toOriginalRadio.setToggleGroup(targetGroup);
        toFlippedRadio.setToggleGroup(targetGroup);
        toOriginalRadio.setSelected(true);

        boolean anyMissingOriginal = collections.stream().anyMatch(g -> g.originalBase == null);
        if (anyMissingOriginal) {
            toOriginalRadio.setText("Original base image (some collections missing original)");
        }

        List<CheckBox> classCheckBoxes = new ArrayList<>();
        VBox classContent = new VBox(4);
        for (String cls : allClasses) {
            CheckBox cb = new CheckBox(cls);
            cb.setSelected(true);
            classCheckBoxes.add(cb);
            classContent.getChildren().add(cb);
        }
        ScrollPane classScroll = new ScrollPane(classContent);
        classScroll.setFitToWidth(true);
        classScroll.setPrefHeight(Math.min(150, allClasses.size() * 28 + 10));

        HBox classButtons = new HBox(8);
        Button checkAll = new Button("Check All");
        Button checkNone = new Button("Check None");
        checkAll.setOnAction(e -> classCheckBoxes.forEach(cb -> cb.setSelected(true)));
        checkNone.setOnAction(e -> classCheckBoxes.forEach(cb -> cb.setSelected(false)));
        classButtons.getChildren().addAll(checkAll, checkNone);

        CheckBox includeMeasurementsCheck = new CheckBox("Include measurements");
        includeMeasurementsCheck.setSelected(true);
        CheckBox lockCheck = new CheckBox("Lock propagated annotations");
        lockCheck.setSelected(true);

        int totalSubImages =
                collections.stream().mapToInt(g -> g.subImages.size()).sum();
        Label summaryLabel = new Label(
                String.format("Found %d collection(s) with %d sub-images total.", collections.size(), totalSubImages));
        summaryLabel.setFont(Font.font("System", 11));

        Label targetLabel = new Label("Target image:");
        targetLabel.setFont(Font.font("System", FontWeight.BOLD, 11));
        Label classLabel = new Label("Annotation classes to propagate:");
        classLabel.setFont(Font.font("System", FontWeight.BOLD, 11));
        Label optionsLabel = new Label("Options:");
        optionsLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        VBox dialogContent = new VBox(8);
        dialogContent.setPadding(new Insets(10));
        javafx.scene.control.Button docButton = DocumentationHelper.createHelpButton("ppmBackPropagate");
        if (docButton != null) {
            HBox helpBar = new HBox(docButton);
            helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            dialogContent.getChildren().add(helpBar);
        }
        dialogContent
                .getChildren()
                .addAll(
                        summaryLabel,
                        new Separator(),
                        targetLabel,
                        toOriginalRadio,
                        toFlippedRadio,
                        new Separator(),
                        classLabel,
                        classButtons,
                        classScroll,
                        new Separator(),
                        optionsLabel,
                        includeMeasurementsCheck,
                        lockCheck);

        boolean confirmed = Dialogs.showConfirmDialog("Back-Propagate Annotations", dialogContent);
        if (!confirmed) return;

        boolean toOriginal = toOriginalRadio.isSelected();
        Set<String> selectedClasses = new LinkedHashSet<>();
        for (CheckBox cb : classCheckBoxes) {
            if (cb.isSelected()) selectedClasses.add(cb.getText());
        }
        if (selectedClasses.isEmpty()) {
            Dialogs.showWarningNotification(
                    "Back-Propagation",
                    DocumentationHelper.withDocLink("No annotation classes selected.", "ppmBackPropagate"));
            return;
        }
        boolean includeMeasurements = includeMeasurementsCheck.isSelected();
        boolean lockAnnotations = lockCheck.isSelected();

        // Log operation parameters for reproducibility
        logger.info("=== PPM BACK-PROPAGATION ===");
        logger.info("Target: {}", toOriginal ? "original base" : "flipped parent");
        logger.info("Classes: {}", selectedClasses);
        logger.info("Include measurements: {}", includeMeasurements);
        logger.info("Lock annotations: {}", lockAnnotations);
        logger.info("Collections: {}", collections.size());

        // Step 4: Execute
        int propagated = executePropagation(
                project, collections, toOriginal, selectedClasses, includeMeasurements, lockAnnotations);

        logger.info("=== PPM BACK-PROPAGATION COMPLETE: {} annotations propagated ===", propagated);

        if (propagated > 0) {
            Dialogs.showInfoNotification(
                    "Back-Propagation", String.format("Propagated %d annotation(s) to parent images.", propagated));
        } else {
            Dialogs.showInfoNotification("Back-Propagation", "No annotations propagated. Check the log for details.");
        }
    }

    // ========================================================================
    // Discovery
    // ========================================================================

    @SuppressWarnings("unchecked")
    static List<CollectionGroup> discoverCollections(Project<BufferedImage> project) {
        Map<Integer, CollectionGroup> groups = new LinkedHashMap<>();

        for (ProjectImageEntry<?> rawEntry : project.getImageList()) {
            ProjectImageEntry<BufferedImage> entry = (ProjectImageEntry<BufferedImage>) rawEntry;
            int collection = ImageMetadataManager.getImageCollection(entry);
            if (collection < 0) continue;

            CollectionGroup group = groups.computeIfAbsent(collection, k -> {
                CollectionGroup g = new CollectionGroup();
                g.collection = k;
                return g;
            });

            if (ImageMetadataManager.isFlipped(entry)) {
                group.flippedParent = entry;
                group.sampleName = ImageMetadataManager.getSampleName(entry);
                String origId = ImageMetadataManager.getOriginalImageId(entry);
                if (origId != null) {
                    for (ProjectImageEntry<?> candidate : project.getImageList()) {
                        if (candidate.getID().equals(origId)) {
                            group.originalBase = (ProjectImageEntry<BufferedImage>) candidate;
                            break;
                        }
                    }
                }
                continue;
            }

            String annotationName = entry.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);
            if (annotationName != null && !annotationName.isEmpty()) {
                group.subImages.add(entry);
            }
        }

        List<CollectionGroup> result = groups.values().stream()
                .filter(g -> g.flippedParent != null && !g.subImages.isEmpty())
                .collect(Collectors.toList());

        for (CollectionGroup g : result) {
            logger.info(
                    "Collection {}: {} sub-images, parent='{}', original='{}'",
                    g.collection,
                    g.subImages.size(),
                    g.flippedParent.getImageName(),
                    g.originalBase != null ? g.originalBase.getImageName() : "none");
        }

        return result;
    }

    static List<String> collectAnnotationClasses(List<CollectionGroup> collections) {
        Set<String> classes = new LinkedHashSet<>();
        for (CollectionGroup group : collections) {
            for (ProjectImageEntry<BufferedImage> subImage : group.subImages) {
                try {
                    ImageData<BufferedImage> imgData = subImage.readImageData();
                    for (PathObject ann : imgData.getHierarchy().getAnnotationObjects()) {
                        PathClass pc = ann.getPathClass();
                        if (pc != null) classes.add(pc.toString());
                    }
                } catch (Exception e) {
                    logger.debug("Could not read annotations from {}: {}", subImage.getImageName(), e.getMessage());
                }
            }
        }
        logger.info("Found {} annotation classes across sub-images: {}", classes.size(), classes);
        return new ArrayList<>(classes);
    }

    // ========================================================================
    // Execution (uses QPSC propagation utilities)
    // ========================================================================

    @SuppressWarnings("unchecked")
    static int executePropagation(
            Project<BufferedImage> project,
            List<CollectionGroup> collections,
            boolean toOriginal,
            Set<String> selectedClasses,
            boolean includeMeasurements,
            boolean lockAnnotations) {

        int totalPropagated = 0;

        for (CollectionGroup group : collections) {
            // Determine target entry
            ProjectImageEntry<BufferedImage> targetEntry;
            boolean applyFlip;

            if (toOriginal && group.originalBase != null) {
                targetEntry = group.originalBase;
                applyFlip = true;
            } else {
                if (toOriginal && group.originalBase == null) {
                    logger.warn("No original base for collection {}. Using flipped parent.", group.collection);
                }
                targetEntry = group.flippedParent;
                applyFlip = false;
            }

            logger.info(
                    "Collection {}: target='{}', applyFlip={}",
                    group.collection,
                    targetEntry.getImageName(),
                    applyFlip);

            // Load alignment transform
            AffineTransform alignment =
                    AffineTransformManager.loadSlideAlignment((Project<BufferedImage>) project, group.sampleName);
            if (alignment == null) {
                logger.warn(
                        "No alignment for sample '{}'. Skipping collection {}.", group.sampleName, group.collection);
                continue;
            }

            // Invert: stage microns -> flipped parent pixels
            AffineTransform stageToFlippedParent;
            try {
                stageToFlippedParent = alignment.createInverse();
            } catch (NoninvertibleTransformException e) {
                logger.error("Cannot invert alignment for '{}'. Skipping.", group.sampleName);
                continue;
            }

            // Load target image data
            ImageData<BufferedImage> targetData;
            try {
                targetData = targetEntry.readImageData();
            } catch (Exception e) {
                logger.error("Cannot load target '{}': {}", targetEntry.getImageName(), e.getMessage());
                continue;
            }

            // Build stage -> target transform (with optional flip to original)
            AffineTransform stageToTarget;
            if (applyFlip) {
                boolean flipX = ImageMetadataManager.isFlippedX(group.flippedParent);
                boolean flipY = ImageMetadataManager.isFlippedY(group.flippedParent);
                int w = targetData.getServer().getWidth();
                int h = targetData.getServer().getHeight();
                // Use QPSC's createFlip utility
                AffineTransform flipTransform = ForwardPropagationWorkflow.createFlip(flipX, flipY, w, h);
                stageToTarget = new AffineTransform(flipTransform);
                stageToTarget.concatenate(stageToFlippedParent);
            } else {
                stageToTarget = stageToFlippedParent;
            }

            int targetWidth = targetData.getServer().getWidth();
            int targetHeight = targetData.getServer().getHeight();

            // Process each sub-image using QPSC transform utilities
            List<PathObject> propagated = new ArrayList<>();

            for (ProjectImageEntry<BufferedImage> subEntry : group.subImages) {
                try {
                    ImageData<BufferedImage> subData = subEntry.readImageData();
                    double subPixelSize =
                            subData.getServer().getPixelCalibration().getPixelWidthMicrons();
                    if (Double.isNaN(subPixelSize) || subPixelSize <= 0) {
                        logger.warn("Invalid pixel size for '{}'. Skipping.", subEntry.getImageName());
                        continue;
                    }

                    double[] xyOffset = ImageMetadataManager.getXYOffset(subEntry);
                    String annotationName = subEntry.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);
                    if (annotationName == null) annotationName = "unknown";

                    // Use QPSC's buildSubToStageTransform (includes half-FOV correction)
                    AffineTransform subToStage =
                            ForwardPropagationWorkflow.buildSubToStageTransform(subPixelSize, xyOffset);

                    // Combined: sub pixel -> target pixel
                    AffineTransform subToTarget = new AffineTransform(stageToTarget);
                    subToTarget.concatenate(subToStage);

                    // Filter annotations by class
                    List<PathObject> sourceAnnotations = subData.getHierarchy().getAnnotationObjects().stream()
                            .filter(ann -> {
                                PathClass pc = ann.getPathClass();
                                return pc != null && selectedClasses.contains(pc.toString());
                            })
                            .collect(Collectors.toList());

                    if (sourceAnnotations.isEmpty()) continue;

                    // Use QPSC's transformAndClip
                    List<PathObject> transformed = ForwardPropagationWorkflow.transformAndClip(
                            sourceAnnotations, subToTarget, targetWidth, targetHeight);

                    // Apply PPM-specific post-processing
                    final String fAnnotationName = annotationName;
                    for (PathObject obj : transformed) {
                        // Prefix name with annotation region
                        String origName = obj.getName();
                        if (origName != null && !origName.isEmpty()) {
                            obj.setName(fAnnotationName + ": " + origName);
                        } else {
                            double cx = obj.getROI().getCentroidX();
                            double cy = obj.getROI().getCentroidY();
                            obj.setName(String.format("%s: (%.0f, %.0f)", fAnnotationName, cx, cy));
                        }
                        if (lockAnnotations) {
                            obj.setLocked(true);
                        }
                    }

                    propagated.addAll(transformed);

                    if (!transformed.isEmpty()) {
                        logger.info(
                                "  {} -> {}: {} annotations (classes: {})",
                                subEntry.getImageName(),
                                targetEntry.getImageName(),
                                transformed.size(),
                                selectedClasses);
                    }

                } catch (Exception e) {
                    logger.warn("Error processing '{}': {}", subEntry.getImageName(), e.getMessage());
                }
            }

            // Save to target
            if (!propagated.isEmpty()) {
                targetData.getHierarchy().addObjects(propagated);
                try {
                    targetEntry.saveImageData(targetData);
                    totalPropagated += propagated.size();
                    logger.info(
                            "Saved {} annotations to '{}' for collection {}",
                            propagated.size(),
                            targetEntry.getImageName(),
                            group.collection);
                } catch (Exception e) {
                    logger.error("Failed to save '{}': {}", targetEntry.getImageName(), e.getMessage());
                }
            }
        }

        return totalPropagated;
    }

    // ========================================================================
    // Programmatic API (for batch workflow integration)
    // ========================================================================

    /**
     * Back-propagates annotations for a single collection. Intended for
     * programmatic use from the batch analysis workflow.
     */
    public static int propagateForCollection(
            Project<BufferedImage> project,
            int collectionNumber,
            Set<String> annotationClasses,
            boolean toOriginal,
            boolean includeMeasurements) {

        logger.info("=== PPM BACK-PROPAGATION (programmatic) ===");
        logger.info(
                "Collection: {}, classes: {}, toOriginal: {}, measurements: {}",
                collectionNumber,
                annotationClasses,
                toOriginal,
                includeMeasurements);

        List<CollectionGroup> allCollections = discoverCollections(project);
        List<CollectionGroup> matching = allCollections.stream()
                .filter(g -> g.collection == collectionNumber)
                .collect(Collectors.toList());

        if (matching.isEmpty()) {
            logger.warn("No collection {} found for back-propagation", collectionNumber);
            return 0;
        }

        int result = executePropagation(project, matching, toOriginal, annotationClasses, includeMeasurements, true);
        logger.info("=== PPM BACK-PROPAGATION COMPLETE: {} annotations ===", result);
        return result;
    }
}
