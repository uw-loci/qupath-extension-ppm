package qupath.ext.ppm.analysis;

import qupath.ext.ppm.PPMPreferences;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * JavaFX control panel for the PPM hue range filter overlay and detection creation.
 *
 * <p>Provides sliders for angle range, saturation/value thresholds, and
 * overlay appearance controls. Changes are debounced and trigger
 * recomputation of the overlay via a callback.</p>
 *
 * <p>Also provides controls for creating QuPath detection objects from the
 * current angle range at a specified resolution level.</p>
 */
public class PPMHueRangePanel extends VBox {

    private static final double DEBOUNCE_MS = 400;

    private final Slider angleLowSlider;
    private final Slider angleHighSlider;
    private final Slider saturationSlider;
    private final Slider valueSlider;
    private final Slider opacitySlider;
    private final ColorPicker colorPicker;
    private final Label statsLabel;
    private final Label angleLowValueLabel;
    private final Label angleHighValueLabel;
    private final Label satValueLabel;
    private final Label valValueLabel;
    private final Label opacityValueLabel;

    // Detection creation controls
    private final ComboBox<String> downsampleCombo;
    private final Spinner<Double> minAreaSpinner;
    private final Button createDetectionsButton;

    private final PauseTransition debounce;

    private Runnable onParametersChanged;
    private Runnable onClear;
    private Runnable onCreateDetections;

    public PPMHueRangePanel() {
        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_LEFT);

        Label title = new Label("PPM Hue Range Filter");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));

        // Angle range
        Label angleLabel = new Label("Angle Range (degrees):");
        angleLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));

        angleLowSlider = createSlider(0, 180, 0);
        angleLowSlider.setTooltip(new Tooltip("Lower bound of the angle range filter (0-180 deg).\n"
                + "Pixels with fiber orientation angles below this value are excluded."));
        angleHighSlider = createSlider(0, 180, 180);
        angleHighSlider.setTooltip(new Tooltip("Upper bound of the angle range filter (0-180 deg).\n"
                + "Pixels with fiber orientation angles above this value are excluded."));
        angleLowValueLabel = new Label("0");
        angleHighValueLabel = new Label("180");

        GridPane angleGrid = new GridPane();
        angleGrid.setHgap(8);
        angleGrid.setVgap(4);
        angleGrid.add(new Label("Low:"), 0, 0);
        angleGrid.add(angleLowSlider, 1, 0);
        angleGrid.add(angleLowValueLabel, 2, 0);
        angleGrid.add(new Label("High:"), 0, 1);
        angleGrid.add(angleHighSlider, 1, 1);
        angleGrid.add(angleHighValueLabel, 2, 1);

        angleLowValueLabel.setMinWidth(35);
        angleHighValueLabel.setMinWidth(35);

        // Thresholds
        Label threshLabel = new Label("Validity Thresholds:");
        threshLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));

        double satDefault = PPMPreferences.getSaturationThreshold();
        double valDefault = PPMPreferences.getValueThreshold();
        saturationSlider = createSlider(0, 1, satDefault);
        saturationSlider.setTooltip(new Tooltip("Minimum saturation threshold for valid pixels.\n"
                + "Pixels with saturation below this value are excluded."));
        valueSlider = createSlider(0, 1, valDefault);
        valueSlider.setTooltip(new Tooltip("Minimum brightness (value) threshold for valid pixels.\n"
                + "Pixels with brightness below this value are excluded."));
        satValueLabel = new Label(String.format("%.2f", satDefault));
        valValueLabel = new Label(String.format("%.2f", valDefault));

        GridPane threshGrid = new GridPane();
        threshGrid.setHgap(8);
        threshGrid.setVgap(4);
        threshGrid.add(new Label("Saturation:"), 0, 0);
        threshGrid.add(saturationSlider, 1, 0);
        threshGrid.add(satValueLabel, 2, 0);
        threshGrid.add(new Label("Value:"), 0, 1);
        threshGrid.add(valueSlider, 1, 1);
        threshGrid.add(valValueLabel, 2, 1);

        satValueLabel.setMinWidth(35);
        valValueLabel.setMinWidth(35);

        // Appearance
        Label appearLabel = new Label("Overlay Appearance:");
        appearLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));

        colorPicker = new ColorPicker(Color.LIME);
        colorPicker.setMaxWidth(80);
        colorPicker.setTooltip(new Tooltip("Choose the color used to highlight matching pixels.\n"
                + "This color is overlaid on the image at the specified opacity."));

        opacitySlider = createSlider(0, 1, 0.5);
        opacitySlider.setTooltip(new Tooltip("Opacity of the highlight overlay (0 = transparent, 1 = opaque)."));
        opacityValueLabel = new Label("0.50");

        GridPane appearGrid = new GridPane();
        appearGrid.setHgap(8);
        appearGrid.setVgap(4);
        appearGrid.add(new Label("Color:"), 0, 0);
        appearGrid.add(colorPicker, 1, 0);
        appearGrid.add(new Label("Opacity:"), 0, 1);
        appearGrid.add(opacitySlider, 1, 1);
        appearGrid.add(opacityValueLabel, 2, 1);

        opacityValueLabel.setMinWidth(35);

        // Stats
        statsLabel = new Label("Adjust sliders to filter by angle range");
        statsLabel.setFont(Font.font("Monospaced", 11));
        statsLabel.setWrapText(true);

        // Clear button
        Button clearButton = new Button("Clear Overlay");
        clearButton.setTooltip(new Tooltip("Remove the hue range filter overlay from the viewer."));
        clearButton.setOnAction(e -> {
            if (onClear != null) onClear.run();
        });

        HBox overlayButtons = new HBox(8, clearButton);
        overlayButtons.setAlignment(Pos.CENTER_LEFT);

        // --- Object Creation Section ---
        Separator separator = new Separator();

        Label detectLabel = new Label("Create Detection Objects:");
        detectLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));

        downsampleCombo = new ComboBox<>();
        downsampleCombo.getItems().addAll("1x (full resolution)", "2x", "4x", "8x", "16x");
        downsampleCombo.setValue("4x");
        downsampleCombo.setMaxWidth(160);
        downsampleCombo.setTooltip(new Tooltip("Resolution level for detection creation.\n"
                + "Higher downsamples are faster but produce coarser objects.\n"
                + "4x is a good balance for most images."));

        minAreaSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 100000, 100, 50));
        minAreaSpinner.setEditable(true);
        minAreaSpinner.setMaxWidth(120);
        minAreaSpinner.setTooltip(new Tooltip("Minimum area in um^2 for created detections.\n"
                + "Objects smaller than this are filtered out as noise."));

        createDetectionsButton = new Button("Create Detections");
        createDetectionsButton.setTooltip(new Tooltip("Create detection objects for regions matching the current\n"
                + "angle range and threshold settings. If an annotation is\n"
                + "selected, detections are created within it. Otherwise,\n"
                + "the full image is processed."));
        createDetectionsButton.setOnAction(e -> {
            if (onCreateDetections != null) onCreateDetections.run();
        });

        GridPane detectGrid = new GridPane();
        detectGrid.setHgap(8);
        detectGrid.setVgap(4);
        detectGrid.add(new Label("Resolution:"), 0, 0);
        detectGrid.add(downsampleCombo, 1, 0);
        detectGrid.add(new Label("Min area (um^2):"), 0, 1);
        detectGrid.add(minAreaSpinner, 1, 1);

        HBox detectButtons = new HBox(8, createDetectionsButton);
        detectButtons.setAlignment(Pos.CENTER_LEFT);

        getChildren()
                .addAll(
                        title,
                        angleLabel,
                        angleGrid,
                        threshLabel,
                        threshGrid,
                        appearLabel,
                        appearGrid,
                        statsLabel,
                        overlayButtons,
                        separator,
                        detectLabel,
                        detectGrid,
                        detectButtons);

        // Debounce timer for overlay parameter changes
        debounce = new PauseTransition(Duration.millis(DEBOUNCE_MS));
        debounce.setOnFinished(e -> {
            if (onParametersChanged != null) onParametersChanged.run();
        });

        // Wire slider listeners
        angleLowSlider.valueProperty().addListener((obs, oldV, newV) -> {
            angleLowValueLabel.setText(String.valueOf(newV.intValue()));
            debounce.playFromStart();
        });
        angleHighSlider.valueProperty().addListener((obs, oldV, newV) -> {
            angleHighValueLabel.setText(String.valueOf(newV.intValue()));
            debounce.playFromStart();
        });
        saturationSlider.valueProperty().addListener((obs, oldV, newV) -> {
            satValueLabel.setText(String.format("%.2f", newV.doubleValue()));
            debounce.playFromStart();
        });
        valueSlider.valueProperty().addListener((obs, oldV, newV) -> {
            valValueLabel.setText(String.format("%.2f", newV.doubleValue()));
            debounce.playFromStart();
        });
        opacitySlider.valueProperty().addListener((obs, oldV, newV) -> {
            opacityValueLabel.setText(String.format("%.2f", newV.doubleValue()));
            debounce.playFromStart();
        });
        colorPicker.setOnAction(e -> debounce.playFromStart());
    }

    /** Sets the callback invoked when any overlay parameter changes (after debounce). */
    public void setOnParametersChanged(Runnable callback) {
        this.onParametersChanged = callback;
    }

    /** Sets the callback invoked when the Clear button is pressed. */
    public void setOnClear(Runnable callback) {
        this.onClear = callback;
    }

    /** Sets the callback invoked when the Create Detections button is pressed. */
    public void setOnCreateDetections(Runnable callback) {
        this.onCreateDetections = callback;
    }

    public float getAngleLow() {
        return (float) angleLowSlider.getValue();
    }

    public float getAngleHigh() {
        return (float) angleHighSlider.getValue();
    }

    public float getSaturationThreshold() {
        return (float) saturationSlider.getValue();
    }

    public float getValueThreshold() {
        return (float) valueSlider.getValue();
    }

    public double getOverlayOpacity() {
        return opacitySlider.getValue();
    }

    /** Returns the highlight color as a packed RGB int (no alpha). */
    public int getHighlightRGB() {
        Color c = colorPicker.getValue();
        int r = (int) (c.getRed() * 255);
        int g = (int) (c.getGreen() * 255);
        int b = (int) (c.getBlue() * 255);
        return (r << 16) | (g << 8) | b;
    }

    /** Returns the selected downsample factor for detection creation. */
    public double getDetectionDownsample() {
        String val = downsampleCombo.getValue();
        if (val == null) return 4.0;
        // Parse "4x" or "1x (full resolution)" -> extract leading number
        String num = val.replaceAll("[^0-9]", "");
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return 4.0;
        }
    }

    /** Returns the minimum area in um^2 for detection creation. */
    public double getMinAreaUm2() {
        return minAreaSpinner.getValue();
    }

    /** Enables or disables the Create Detections button (e.g., while processing). */
    public void setCreateDetectionsEnabled(boolean enabled) {
        createDetectionsButton.setDisable(!enabled);
    }

    /** Updates the stats display with current overlay results. */
    public void updateStats(int matchingPixels, int totalValidPixels) {
        if (totalValidPixels == 0) {
            statsLabel.setText("No valid pixels found");
            return;
        }
        double pct = 100.0 * matchingPixels / totalValidPixels;
        statsLabel.setText(String.format(
                "Matching: %,d / %,d valid pixels (%.1f%%)\n" + "Angle range: %.0f - %.0f deg (visible area)",
                matchingPixels, totalValidPixels, pct, angleLowSlider.getValue(), angleHighSlider.getValue()));
    }

    private static Slider createSlider(double min, double max, double value) {
        Slider slider = new Slider(min, max, value);
        slider.setPrefWidth(180);
        slider.setBlockIncrement(1);
        return slider;
    }
}
