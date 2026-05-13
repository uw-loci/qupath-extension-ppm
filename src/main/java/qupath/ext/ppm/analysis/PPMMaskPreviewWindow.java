package qupath.ext.ppm.analysis;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.awt.image.WritableRaster;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.analysis.PPMImageSetDiscovery.PPMAnalysisSet;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;

/**
 * Live preview popup for the perpendicularity foreground mask. Shows a small region
 * (around the current viewer center or selected annotation) with a red overlay marking
 * the pixels that would be EXCLUDED by the active thresholds. Two modes: HSV/intensity
 * exclusions (sat/val/min-RGB applied to the current RGB viewer image) and biref-threshold
 * exclusions (applied to the biref sibling image).
 *
 * <p>Region size is intentionally small (default 256 px) for fast feedback; the slider
 * controls overlay opacity, and recomputation happens automatically when any bound
 * spinner value changes.</p>
 */
final class PPMMaskPreviewWindow {

    private static final Logger logger = LoggerFactory.getLogger(PPMMaskPreviewWindow.class);

    private static final int OVERLAY_R = 255;
    private static final int OVERLAY_G = 30;
    private static final int OVERLAY_B = 30;

    private PPMMaskPreviewWindow() {}

    static void show(
            QuPathGUI gui,
            ImageData<BufferedImage> currentImageData,
            PPMAnalysisSet analysisSet,
            Spinner<Double> satSpinner,
            Spinner<Double> valSpinner,
            Spinner<Integer> minIntensitySpinner,
            Spinner<Double> birefSpinner) {

        if (gui == null || currentImageData == null) {
            Dialogs.showWarningNotification("Perpendicularity preview", "No image is open.");
            return;
        }

        Stage stage = new Stage();
        stage.initOwner(gui.getStage());
        stage.setTitle("Foreground mask preview");

        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        // Fixed display size so the window layout is stable regardless of region size.
        // Smaller regions scale up (pixelated, matching the analysis resolution); larger
        // regions scale down to fit.
        imageView.setFitWidth(400);
        imageView.setFitHeight(400);

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton hsvMode = new RadioButton("HSV/intensity exclusions");
        hsvMode.setToggleGroup(modeGroup);
        hsvMode.setTooltip(new Tooltip("Red = pixels excluded by saturation / value / min-RGB-intensity"));
        RadioButton birefMode = new RadioButton("Biref threshold");
        birefMode.setToggleGroup(modeGroup);
        birefMode.setTooltip(new Tooltip("Red = pixels below the biref-image threshold (only)"));
        hsvMode.setSelected(true);

        boolean hasBiref = analysisSet != null && analysisSet.hasBirefImage();
        if (!hasBiref) {
            birefMode.setDisable(true);
            birefMode.setTooltip(new Tooltip("No biref sibling found for the current image"));
        }
        boolean currentIsRGB = currentImageData.getServer().nChannels() >= 3;
        if (!currentIsRGB) {
            hsvMode.setDisable(true);
            hsvMode.setTooltip(new Tooltip("Current image is not RGB; HSV mode unavailable"));
            if (hasBiref) {
                birefMode.setSelected(true);
            }
        }

        ChoiceBox<Integer> sizeChoice = new ChoiceBox<>();
        sizeChoice.getItems().addAll(128, 256, 512);
        sizeChoice.setValue(256);
        sizeChoice.setTooltip(new Tooltip("Preview region size in pixels (smaller = faster refresh)"));

        Slider opacitySlider = new Slider(0, 1, 0.6);
        opacitySlider.setShowTickLabels(false);
        opacitySlider.setShowTickMarks(false);
        opacitySlider.setPrefWidth(180);

        Label info = new Label();
        info.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        // Cached sampled data
        final BufferedImage[] sampledRGB = new BufferedImage[1];
        final short[][] sampledBiref = new short[1][];
        final int[] sampledW = {0};
        final int[] sampledH = {0};
        final int[] sampledX = {0};
        final int[] sampledY = {0};

        Runnable resample = () -> {
            QuPathViewer viewer = gui.getViewer();
            if (viewer == null) {
                info.setText("No active viewer.");
                return;
            }
            int regionSize = sizeChoice.getValue();
            ImageServer<BufferedImage> server = currentImageData.getServer();
            // Centre on the selected annotation centroid if exactly one is selected; else viewer centre.
            double cx, cy;
            PathObject selected = viewer.getSelectedObject();
            if (selected != null && selected.getROI() != null) {
                cx = selected.getROI().getCentroidX();
                cy = selected.getROI().getCentroidY();
            } else {
                cx = viewer.getCenterPixelX();
                cy = viewer.getCenterPixelY();
            }
            int x = (int) Math.max(0, Math.min(server.getWidth() - regionSize, cx - regionSize / 2.0));
            int y = (int) Math.max(0, Math.min(server.getHeight() - regionSize, cy - regionSize / 2.0));
            int w = Math.min(regionSize, server.getWidth() - x);
            int h = Math.min(regionSize, server.getHeight() - y);
            try {
                RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, x, y, w, h);
                sampledRGB[0] = server.readRegion(req);
                sampledW[0] = w;
                sampledH[0] = h;
                sampledX[0] = x;
                sampledY[0] = y;
                sampledBiref[0] = null; // invalidate; lazily fetched
                info.setText(String.format("Region: %dx%d at (%d,%d)", w, h, x, y));
            } catch (Exception ex) {
                logger.warn("Preview resample failed: {}", ex.getMessage());
                info.setText("Failed to read region: " + ex.getMessage());
            }
        };

        Runnable ensureBirefSampled = () -> {
            if (sampledBiref[0] != null || !hasBiref || sampledRGB[0] == null) return;
            try {
                @SuppressWarnings("unchecked")
                ImageData<BufferedImage> birefData = (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
                try (ImageServer<BufferedImage> birefServer = birefData.getServer()) {
                    int w = sampledW[0];
                    int h = sampledH[0];
                    int x = Math.max(0, Math.min(birefServer.getWidth() - w, sampledX[0]));
                    int y = Math.max(0, Math.min(birefServer.getHeight() - h, sampledY[0]));
                    RegionRequest req = RegionRequest.createInstance(birefServer.getPath(), 1.0, x, y, w, h);
                    BufferedImage birefImg = birefServer.readRegion(req);
                    short[] shorts = new short[w * h];
                    if (birefImg.getRaster().getDataBuffer() instanceof DataBufferUShort dbus) {
                        short[] src = dbus.getData();
                        System.arraycopy(src, 0, shorts, 0, Math.min(src.length, shorts.length));
                    } else {
                        // Fallback: read samples (handles 8-bit or other layouts as 16-bit-ish)
                        WritableRaster r = birefImg.getRaster();
                        int[] samples = new int[w * h];
                        r.getSamples(0, 0, w, h, 0, samples);
                        for (int i = 0; i < samples.length; i++) {
                            shorts[i] = (short) (samples[i] & 0xFFFF);
                        }
                    }
                    sampledBiref[0] = shorts;
                }
            } catch (Exception ex) {
                logger.warn("Preview biref sample failed: {}", ex.getMessage());
            }
        };

        Runnable redraw = () -> {
            BufferedImage src = sampledRGB[0];
            if (src == null) return;
            int w = sampledW[0];
            int h = sampledH[0];
            double alpha = opacitySlider.getValue();
            boolean isBirefMode = birefMode.isSelected();

            double satT = satSpinner.getValue();
            double valT = valSpinner.getValue();
            int minRGB = minIntensitySpinner.getValue();
            double birefT = birefSpinner.getValue();

            short[] biref = null;
            if (isBirefMode) {
                ensureBirefSampled.run();
                biref = sampledBiref[0];
            }

            WritableImage out = new WritableImage(w, h);
            PixelWriter pw = out.getPixelWriter();
            float[] hsb = new float[3];
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    int argb = src.getRGB(xx, yy);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;

                    boolean excluded;
                    if (isBirefMode) {
                        if (biref == null) {
                            excluded = false;
                        } else {
                            int bi = biref[yy * w + xx] & 0xFFFF;
                            excluded = bi < birefT;
                        }
                    } else {
                        Color.RGBtoHSB(r, g, b, hsb);
                        boolean valid = hsb[1] >= satT && hsb[2] >= valT;
                        if (valid && minRGB > 0) {
                            int maxRGB = Math.max(r, Math.max(g, b));
                            if (maxRGB < minRGB) valid = false;
                        }
                        if (valid && (r == 255 || g == 255 || b == 255)) valid = false;
                        excluded = !valid;
                    }

                    int rr, gg, bb;
                    if (excluded) {
                        rr = (int) (r * (1 - alpha) + OVERLAY_R * alpha);
                        gg = (int) (g * (1 - alpha) + OVERLAY_G * alpha);
                        bb = (int) (b * (1 - alpha) + OVERLAY_B * alpha);
                    } else {
                        rr = r;
                        gg = g;
                        bb = b;
                    }
                    pw.setArgb(xx, yy, (0xFF << 24) | (rr << 16) | (gg << 8) | bb);
                }
            }
            imageView.setImage(out);
        };

        // Wire listeners: any spinner / opacity / mode / size change triggers redraw.
        // Size changes also force a resample.
        ChangeListener<Object> redrawListener = (obs, ov, nv) -> redraw.run();
        opacitySlider.valueProperty().addListener(redrawListener);
        modeGroup.selectedToggleProperty().addListener(redrawListener);
        satSpinner.valueProperty().addListener(redrawListener);
        valSpinner.valueProperty().addListener(redrawListener);
        minIntensitySpinner.valueProperty().addListener(redrawListener);
        birefSpinner.valueProperty().addListener(redrawListener);
        sizeChoice.valueProperty().addListener((obs, ov, nv) -> {
            resample.run();
            redraw.run();
        });

        javafx.scene.control.Button resampleBtn = new javafx.scene.control.Button("Resample at current view");
        resampleBtn.setOnAction(e -> {
            resample.run();
            redraw.run();
        });

        // Initial sample + draw synchronously so the window opens fully populated.
        resample.run();
        redraw.run();

        HBox modeRow = new HBox(12, hsvMode, birefMode);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        HBox controlRow = new HBox(8, new Label("Size:"), sizeChoice, resampleBtn);
        controlRow.setAlignment(Pos.CENTER_LEFT);
        HBox opacityRow = new HBox(8, new Label("Opacity:"), opacitySlider);
        opacityRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(6, modeRow, controlRow, imageView, opacityRow, info);
        root.setPadding(new Insets(8));
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.show();
    }
}
