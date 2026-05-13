package qupath.ext.ppm.analysis;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.awt.image.WritableRaster;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.PPMPreferences;
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
 * (around the current viewer center or selected annotation) and overlays the pixels
 * that would be EXCLUDED by the active thresholds. Two modes: HSV/intensity exclusions
 * (sat/val/min-RGB applied to the current RGB viewer image) and biref-threshold
 * exclusions (applied to the biref sibling image). Each mode has its own pre-threshold
 * Gaussian blur. When the parent dialog's pixel-classifier radio is selected, the
 * biref mode is hidden because biref thresholds aren't applied in that mode.
 *
 * <p>Region size is intentionally small (default 256 px) for fast feedback; the slider
 * controls overlay opacity, the colour picker selects the overlay colour (defaults to a
 * non-pink/red hue so it stays visible on H&E), and recomputation happens automatically
 * when any bound spinner value changes.</p>
 */
final class PPMMaskPreviewWindow {

    private static final Logger logger = LoggerFactory.getLogger(PPMMaskPreviewWindow.class);

    private PPMMaskPreviewWindow() {}

    static void show(
            QuPathGUI gui,
            ImageData<BufferedImage> currentImageData,
            PPMAnalysisSet analysisSet,
            Spinner<Double> satSpinner,
            Spinner<Double> valSpinner,
            Spinner<Integer> minIntensitySpinner,
            Spinner<Double> birefSpinner,
            Spinner<Double> hsvBlurSpinner,
            Spinner<Double> birefBlurSpinner,
            BooleanProperty classifierModeProperty) {

        if (gui == null || currentImageData == null) {
            Dialogs.showWarningNotification("Perpendicularity preview", "No image is open.");
            return;
        }

        Stage stage = new Stage();
        stage.initOwner(gui.getStage());
        stage.setTitle("Masks showing excluded pixels");

        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        imageView.setFitWidth(400);
        imageView.setFitHeight(400);

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton hsvMode = new RadioButton("HSV/intensity");
        hsvMode.setToggleGroup(modeGroup);
        hsvMode.setTooltip(new Tooltip("Pixels excluded by saturation / value / min-RGB-intensity"));
        RadioButton birefMode = new RadioButton("Biref threshold");
        birefMode.setToggleGroup(modeGroup);
        birefMode.setTooltip(new Tooltip("Pixels below the biref-image threshold"));
        hsvMode.setSelected(true);

        boolean hasBiref = analysisSet != null && analysisSet.hasBirefImage();
        boolean currentIsRGB = currentImageData.getServer().nChannels() >= 3;
        boolean classifierOn = classifierModeProperty != null && classifierModeProperty.get();

        // In classifier mode the biref threshold is unused -- hide the biref radio entirely.
        // When toggled at runtime, react too.
        Runnable applyMode = () -> {
            boolean cls = classifierModeProperty != null && classifierModeProperty.get();
            boolean birefAvailable = hasBiref && !cls;
            birefMode.setVisible(birefAvailable);
            birefMode.setManaged(birefAvailable);
            if (!birefAvailable && birefMode.isSelected()) {
                hsvMode.setSelected(true);
            }
        };
        if (!hasBiref) {
            birefMode.setDisable(true);
            birefMode.setTooltip(new Tooltip("No biref sibling found for the current image"));
        }
        if (!currentIsRGB) {
            hsvMode.setDisable(true);
            hsvMode.setTooltip(new Tooltip("Current image is not RGB; HSV mode unavailable"));
            if (hasBiref && !classifierOn) {
                birefMode.setSelected(true);
            }
        }
        applyMode.run();

        ChoiceBox<Integer> sizeChoice = new ChoiceBox<>();
        sizeChoice.getItems().addAll(128, 256, 512);
        sizeChoice.setValue(256);
        sizeChoice.setTooltip(new Tooltip("Preview region size in pixels (smaller = faster refresh)"));

        Slider opacitySlider = new Slider(0, 1, 0.6);
        opacitySlider.setShowTickLabels(false);
        opacitySlider.setShowTickMarks(false);
        opacitySlider.setPrefWidth(180);

        ColorPicker colorPicker = new ColorPicker(parseHexColor(PPMPreferences.getMaskOverlayColor()));
        colorPicker.setTooltip(new Tooltip("Overlay colour for excluded pixels. Default avoids red/pink to\n"
                + "remain visible on H&E and collagen hues."));

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
                sampledBiref[0] = null;
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
            boolean isBirefMode = birefMode.isSelected() && birefMode.isVisible();

            double satT = satSpinner.getValue();
            double valT = valSpinner.getValue();
            int minRGB = minIntensitySpinner.getValue();
            double birefT = birefSpinner.getValue();
            double hsvBlur = hsvBlurSpinner == null ? 0 : hsvBlurSpinner.getValue();
            double birefBlur = birefBlurSpinner == null ? 0 : birefBlurSpinner.getValue();

            javafx.scene.paint.Color fxColor = colorPicker.getValue();
            int overlayR = (int) Math.round(fxColor.getRed() * 255);
            int overlayG = (int) Math.round(fxColor.getGreen() * 255);
            int overlayB = (int) Math.round(fxColor.getBlue() * 255);

            // Source pixels for the BACKDROP (always unblurred so the user recognises the image).
            int n = w * h;
            int[] argbBackdrop = new int[n];
            src.getRGB(0, 0, w, h, argbBackdrop, 0, w);

            // Source pixels used for THRESHOLDING (optionally blurred).
            int[] rgbForThresh = argbBackdrop;
            short[] birefForThresh = null;
            if (isBirefMode) {
                ensureBirefSampled.run();
                short[] biref = sampledBiref[0];
                if (biref != null && birefBlur > 0) {
                    birefForThresh = gaussianBlurShort(biref, w, h, birefBlur);
                } else {
                    birefForThresh = biref;
                }
            } else if (hsvBlur > 0) {
                rgbForThresh = gaussianBlurRGB(argbBackdrop, w, h, hsvBlur);
            }

            WritableImage out = new WritableImage(w, h);
            PixelWriter pw = out.getPixelWriter();
            float[] hsb = new float[3];
            for (int i = 0; i < n; i++) {
                int back = argbBackdrop[i];
                int r = (back >> 16) & 0xFF;
                int g = (back >> 8) & 0xFF;
                int b = back & 0xFF;

                boolean excluded;
                if (isBirefMode) {
                    if (birefForThresh == null) {
                        excluded = false;
                    } else {
                        int bi = birefForThresh[i] & 0xFFFF;
                        excluded = bi < birefT;
                    }
                } else {
                    int rgbT = rgbForThresh[i];
                    int rr = (rgbT >> 16) & 0xFF;
                    int gg = (rgbT >> 8) & 0xFF;
                    int bb = rgbT & 0xFF;
                    Color.RGBtoHSB(rr, gg, bb, hsb);
                    boolean valid = hsb[1] >= satT && hsb[2] >= valT;
                    if (valid && minRGB > 0) {
                        int maxRGB = Math.max(rr, Math.max(gg, bb));
                        if (maxRGB < minRGB) valid = false;
                    }
                    if (valid && (rr == 255 || gg == 255 || bb == 255)) valid = false;
                    excluded = !valid;
                }

                int dr, dg, db;
                if (excluded) {
                    dr = (int) (r * (1 - alpha) + overlayR * alpha);
                    dg = (int) (g * (1 - alpha) + overlayG * alpha);
                    db = (int) (b * (1 - alpha) + overlayB * alpha);
                } else {
                    dr = r;
                    dg = g;
                    db = b;
                }
                int x = i % w;
                int y = i / w;
                pw.setArgb(x, y, (0xFF << 24) | (dr << 16) | (dg << 8) | db);
            }
            imageView.setImage(out);
        };

        // Listeners
        ChangeListener<Object> redrawListener = (obs, ov, nv) -> redraw.run();
        opacitySlider.valueProperty().addListener(redrawListener);
        modeGroup.selectedToggleProperty().addListener(redrawListener);
        satSpinner.valueProperty().addListener(redrawListener);
        valSpinner.valueProperty().addListener(redrawListener);
        minIntensitySpinner.valueProperty().addListener(redrawListener);
        birefSpinner.valueProperty().addListener(redrawListener);
        if (hsvBlurSpinner != null) {
            hsvBlurSpinner.valueProperty().addListener(redrawListener);
        }
        if (birefBlurSpinner != null) {
            birefBlurSpinner.valueProperty().addListener(redrawListener);
        }
        if (classifierModeProperty != null) {
            classifierModeProperty.addListener((obs, ov, nv) -> {
                applyMode.run();
                redraw.run();
            });
        }
        colorPicker.valueProperty().addListener((obs, ov, nv) -> {
            PPMPreferences.setMaskOverlayColor(toHexColor(nv));
            redraw.run();
        });
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

        javafx.scene.control.Button helpBtn = new javafx.scene.control.Button("?");
        helpBtn.setTooltip(new Tooltip("What do these modes do?"));
        helpBtn.setOnAction(e -> Dialogs.showMessageDialog(
                "Mask preview help",
                "HSV/Intensity:\n"
                        + "Use the HSV and min pixel intensity settings to exclude dark\n"
                        + "objects like nuclei or saturated areas.\n\n"
                        + "Biref threshold:\n"
                        + "Adjust the Birefringence threshold and blur to ensure you are\n"
                        + "maximizing the amount of collagen you are detecting, while\n"
                        + "minimizing lost signal."));
        Region modeSpacer = new Region();
        HBox.setHgrow(modeSpacer, Priority.ALWAYS);
        HBox modeRow = new HBox(12, hsvMode, birefMode, modeSpacer, helpBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        HBox controlRow = new HBox(8, new Label("Size:"), sizeChoice, resampleBtn);
        controlRow.setAlignment(Pos.CENTER_LEFT);
        HBox opacityRow = new HBox(8, new Label("Opacity:"), opacitySlider, new Label("Color:"), colorPicker);
        opacityRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(6, modeRow, controlRow, imageView, opacityRow, info);
        root.setPadding(new Insets(8));
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.show();
    }

    private static javafx.scene.paint.Color parseHexColor(String hex) {
        try {
            return javafx.scene.paint.Color.web(hex);
        } catch (Exception e) {
            return javafx.scene.paint.Color.web("#00E5FF");
        }
    }

    private static String toHexColor(javafx.scene.paint.Color c) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static float[] gaussianKernel1D(double sigma) {
        int radius = Math.max(1, (int) Math.ceil(3.0 * sigma));
        int size = 2 * radius + 1;
        float[] k = new float[size];
        double s2 = 2 * sigma * sigma;
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            double v = Math.exp(-(i * i) / s2);
            k[i + radius] = (float) v;
            sum += v;
        }
        for (int i = 0; i < size; i++) k[i] /= (float) sum;
        return k;
    }

    private static int[] gaussianBlurRGB(int[] argb, int w, int h, double sigma) {
        float[] kernel = gaussianKernel1D(sigma);
        int radius = kernel.length / 2;
        int n = w * h;
        float[] rArr = new float[n], gArr = new float[n], bArr = new float[n];
        for (int i = 0; i < n; i++) {
            rArr[i] = (argb[i] >> 16) & 0xFF;
            gArr[i] = (argb[i] >> 8) & 0xFF;
            bArr[i] = argb[i] & 0xFF;
        }
        float[] rTmp = new float[n], gTmp = new float[n], bTmp = new float[n];
        // Horizontal pass
        for (int yy = 0; yy < h; yy++) {
            int row = yy * w;
            for (int xx = 0; xx < w; xx++) {
                float sr = 0, sg = 0, sb = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xk = Math.min(w - 1, Math.max(0, xx + k));
                    float weight = kernel[k + radius];
                    int idx = row + xk;
                    sr += rArr[idx] * weight;
                    sg += gArr[idx] * weight;
                    sb += bArr[idx] * weight;
                }
                rTmp[row + xx] = sr;
                gTmp[row + xx] = sg;
                bTmp[row + xx] = sb;
            }
        }
        // Vertical pass
        int[] out = new int[n];
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                float sr = 0, sg = 0, sb = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yk = Math.min(h - 1, Math.max(0, yy + k));
                    float weight = kernel[k + radius];
                    int idx = yk * w + xx;
                    sr += rTmp[idx] * weight;
                    sg += gTmp[idx] * weight;
                    sb += bTmp[idx] * weight;
                }
                int rOut = Math.max(0, Math.min(255, Math.round(sr)));
                int gOut = Math.max(0, Math.min(255, Math.round(sg)));
                int bOut = Math.max(0, Math.min(255, Math.round(sb)));
                out[yy * w + xx] = (0xFF << 24) | (rOut << 16) | (gOut << 8) | bOut;
            }
        }
        return out;
    }

    private static short[] gaussianBlurShort(short[] data, int w, int h, double sigma) {
        float[] kernel = gaussianKernel1D(sigma);
        int radius = kernel.length / 2;
        int n = w * h;
        float[] tmp = new float[n];
        // Horizontal
        for (int yy = 0; yy < h; yy++) {
            int row = yy * w;
            for (int xx = 0; xx < w; xx++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xk = Math.min(w - 1, Math.max(0, xx + k));
                    s += (data[row + xk] & 0xFFFF) * kernel[k + radius];
                }
                tmp[row + xx] = s;
            }
        }
        short[] out = new short[n];
        // Vertical
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yk = Math.min(h - 1, Math.max(0, yy + k));
                    s += tmp[yk * w + xx] * kernel[k + radius];
                }
                int v = Math.max(0, Math.min(65535, Math.round(s)));
                out[yy * w + xx] = (short) v;
            }
        }
        return out;
    }
}
