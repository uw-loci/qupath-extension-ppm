package qupath.ext.ppm.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * Configuration dialog for the PPM polarity plot workflow.
 *
 * <p>Lets the user choose between (a) the currently selected objects, or
 * (b) all annotations / detections in the image filtered by class. The
 * resulting {@link Result} is consumed by {@link PPMPolarityPlotWorkflow}
 * to drive a multi-object run that writes per-object polarity measurements
 * and displays an aggregate polar histogram.
 */
public final class PolarityPlotConfigDialog {

    public enum ObjectType {
        CURRENT_SELECTION,
        ANNOTATIONS,
        DETECTIONS
    }

    /** Sentinel pseudo-class name used to represent unclassified objects in the class list. */
    public static final String UNCLASSIFIED_LABEL = "(Unclassified)";

    public static final class Result {
        public final ObjectType type;
        /** Class display names (or {@link #UNCLASSIFIED_LABEL}) the user picked. Empty when type==CURRENT_SELECTION. */
        public final Set<String> classNames;
        /** Resolved list of objects to run analysis on, derived from the hierarchy + filters. */
        public final List<PathObject> resolvedObjects;

        Result(ObjectType type, Set<String> classNames, List<PathObject> resolvedObjects) {
            this.type = type;
            this.classNames = classNames;
            this.resolvedObjects = resolvedObjects;
        }
    }

    private PolarityPlotConfigDialog() {}

    /**
     * Shows the modal dialog and blocks until the user picks Run or Cancel.
     *
     * @return populated {@link Result}, or empty if the user cancelled / picked an empty set
     */
    public static Optional<Result> show(Window owner, ImageData<?> imageData) {
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        Collection<PathObject> selected = hierarchy.getSelectionModel().getSelectedObjects();

        Stage dialog = new Stage();
        dialog.setTitle("PPM Polarity Plot - Run on Objects");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));

        int row = 0;

        Label header = new Label("Choose which objects to analyze.");
        header.setStyle("-fx-font-weight: bold;");
        grid.add(header, 0, row, 2, 1);
        row++;

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton useSelection = new RadioButton("Use current selection (" + selected.size() + " objects)");
        useSelection.setToggleGroup(typeGroup);
        useSelection.setUserData(ObjectType.CURRENT_SELECTION);
        useSelection.setDisable(selected.isEmpty());

        RadioButton allAnnotations = new RadioButton("Annotations by class");
        allAnnotations.setToggleGroup(typeGroup);
        allAnnotations.setUserData(ObjectType.ANNOTATIONS);

        RadioButton allDetections = new RadioButton("Detections by class");
        allDetections.setToggleGroup(typeGroup);
        allDetections.setUserData(ObjectType.DETECTIONS);

        grid.add(useSelection, 0, row, 2, 1);
        row++;
        grid.add(allAnnotations, 0, row, 2, 1);
        row++;
        grid.add(allDetections, 0, row, 2, 1);
        row++;

        Label classLabel = new Label("Classes to include:");
        grid.add(classLabel, 0, row, 2, 1);
        row++;

        ListView<String> classList = new ListView<>();
        classList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        classList.setPrefHeight(180);
        VBox.setVgrow(classList, Priority.ALWAYS);
        grid.add(classList, 0, row, 2, 1);
        row++;

        Label countLabel = new Label("");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        grid.add(countLabel, 0, row, 2, 1);
        row++;

        // Default to current selection when available, otherwise annotations
        if (!selected.isEmpty()) {
            useSelection.setSelected(true);
        } else {
            allAnnotations.setSelected(true);
        }

        Runnable refreshClassList = () -> {
            Object data = typeGroup.getSelectedToggle() == null
                    ? null
                    : typeGroup.getSelectedToggle().getUserData();
            if (data == ObjectType.CURRENT_SELECTION || data == null) {
                classList.setDisable(true);
                classList.setItems(FXCollections.emptyObservableList());
                int n = selected.size();
                countLabel.setText(
                        n == 0 ? "No objects selected." : n + " object" + (n == 1 ? "" : "s") + " in selection.");
                return;
            }
            classList.setDisable(false);
            boolean wantAnnotations = data == ObjectType.ANNOTATIONS;
            Collection<PathObject> pool =
                    wantAnnotations ? hierarchy.getAnnotationObjects() : hierarchy.getDetectionObjects();
            Map<String, Integer> counts = countByClass(pool);
            List<String> names = new ArrayList<>(counts.keySet());
            classList.setItems(FXCollections.observableArrayList(names));
            classList.getSelectionModel().selectAll();
            Runnable updateCount = () -> {
                int total = classList.getSelectionModel().getSelectedItems().stream()
                        .mapToInt(c -> counts.getOrDefault(c, 0))
                        .sum();
                countLabel.setText(total + " " + (wantAnnotations ? "annotation" : "detection")
                        + (total == 1 ? "" : "s")
                        + " across "
                        + classList.getSelectionModel().getSelectedItems().size()
                        + " class(es).");
            };
            classList.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<String>)
                    c -> updateCount.run());
            updateCount.run();
        };
        typeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> refreshClassList.run());
        refreshClassList.run();

        Button runButton = new Button("Run");
        runButton.setDefaultButton(true);
        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        HBox buttons = new HBox(10, runButton, cancelButton);
        buttons.setPadding(new Insets(10, 0, 0, 0));
        grid.add(buttons, 0, row, 2, 1);

        Result[] holder = new Result[1];
        runButton.setOnAction(e -> {
            ObjectType type = (ObjectType) typeGroup.getSelectedToggle().getUserData();
            if (type == ObjectType.CURRENT_SELECTION) {
                List<PathObject> objs = new ArrayList<>(selected);
                if (objs.isEmpty()) return;
                holder[0] = new Result(type, Collections.emptySet(), objs);
            } else {
                Set<String> chosen = new HashSet<>(classList.getSelectionModel().getSelectedItems());
                if (chosen.isEmpty()) return;
                Collection<PathObject> pool = type == ObjectType.ANNOTATIONS
                        ? hierarchy.getAnnotationObjects()
                        : hierarchy.getDetectionObjects();
                List<PathObject> objs = new ArrayList<>();
                for (PathObject obj : pool) {
                    if (obj.getROI() == null) continue;
                    String name = classNameFor(obj);
                    if (chosen.contains(name)) objs.add(obj);
                }
                if (objs.isEmpty()) return;
                holder[0] = new Result(type, chosen, objs);
            }
            dialog.close();
        });
        cancelButton.setOnAction(e -> dialog.close());

        Scene scene = new Scene(grid, 460, 440);
        dialog.setScene(scene);
        dialog.showAndWait();

        return Optional.ofNullable(holder[0]);
    }

    private static String classNameFor(PathObject obj) {
        PathClass pc = obj.getPathClass();
        return pc == null ? UNCLASSIFIED_LABEL : pc.toString();
    }

    private static Map<String, Integer> countByClass(Collection<PathObject> pool) {
        // TreeMap for stable alphabetical ordering; unclassified pinned to the bottom by suffixing.
        Map<String, Integer> counts = new TreeMap<>();
        int unclassified = 0;
        for (PathObject obj : pool) {
            if (obj.getROI() == null) continue;
            PathClass pc = obj.getPathClass();
            if (pc == null) {
                unclassified++;
            } else {
                counts.merge(pc.toString(), 1, Integer::sum);
            }
        }
        if (unclassified > 0) counts.put(UNCLASSIFIED_LABEL, unclassified);
        return counts;
    }
}
