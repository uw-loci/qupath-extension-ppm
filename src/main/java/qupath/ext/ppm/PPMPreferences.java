package qupath.ext.ppm;

import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * PPM analysis preferences -- thresholds, histogram bins, and calibration path.
 *
 * <p>Hardware preferences (angle selection, exposure times, overrides) are owned by
 * QPSC's {@code qupath.ext.qpsc.modality.ppm.PPMPreferences}. This class holds only
 * the analysis/processing parameters used by the PPM analysis extension.</p>
 *
 * <p>The active calibration path is shared: QPSC writes it during sunburst calibration,
 * PPM reads it during analysis. Both classes access the same global preference key.</p>
 */
public class PPMPreferences {

    private static final Logger logger = LoggerFactory.getLogger(PPMPreferences.class);

    // =============== Analysis Parameters ===============

    private static final StringProperty birefringenceThreshold =
            PathPrefs.createPersistentPreference("PPMBirefringenceThreshold", "100.0");

    private static final StringProperty histogramBins = PathPrefs.createPersistentPreference("PPMHistogramBins", "18");

    private static final StringProperty saturationThreshold =
            PathPrefs.createPersistentPreference("PPMSaturationThreshold", "0.2");

    private static final StringProperty valueThreshold =
            PathPrefs.createPersistentPreference("PPMValueThreshold", "0.2");

    private static final StringProperty dilationUm = PathPrefs.createPersistentPreference("PPMDilationUm", "50.0");

    private static final StringProperty tacsThresholdDeg =
            PathPrefs.createPersistentPreference("PPMTacsThresholdDeg", "30.0");

    private static final StringProperty minPolylineLengthPx =
            PathPrefs.createPersistentPreference("PPMMinPolylineLengthPx", "20");

    private static final StringProperty minRgbIntensity =
            PathPrefs.createPersistentPreference("PPMMinRgbIntensity", "100");

    private static final StringProperty perpBoundaryClass =
            PathPrefs.createPersistentPreference("PPMPerpBoundaryClass", "");

    private static final StringProperty birefBlurSigma =
            PathPrefs.createPersistentPreference("PPMBirefBlurSigma", "0.0");

    private static final StringProperty hsvBlurSigma = PathPrefs.createPersistentPreference("PPMHsvBlurSigma", "0.0");

    /** Hex web colour (e.g. "#00E5FF") used as the preview-mask overlay. */
    private static final StringProperty maskOverlayColor =
            PathPrefs.createPersistentPreference("PPMMaskOverlayColor", "#00E5FF");

    // ---- Group A: top-level analysis defaults --------------------------------

    private static final StringProperty zoneMode = PathPrefs.createPersistentPreference("PPMZoneMode", "outside");

    private static final StringProperty fillHoles = PathPrefs.createPersistentPreference("PPMFillHoles", "true");

    /** True = classifier mode, false = HSV/biref threshold mode. */
    private static final StringProperty useClassifier =
            PathPrefs.createPersistentPreference("PPMUseClassifier", "false");

    // ---- Group B: extended-TACS block ----------------------------------------

    private static final StringProperty extendedTacsEnabled =
            PathPrefs.createPersistentPreference("PPMExtendedTACSEnabled", "false");

    private static final StringProperty minCollagenDensity =
            PathPrefs.createPersistentPreference("PPMMinCollagenDensity", "0.1");

    private static final StringProperty minSignalThreshold =
            PathPrefs.createPersistentPreference("PPMMinSignalThreshold", "0.02");

    // ---- Group C: smoothing & cleanup ---------------------------------------

    private static final StringProperty boundarySmoothing =
            PathPrefs.createPersistentPreference("PPMBoundarySmoothing", "5.0");

    private static final StringProperty tacsContourSmoothing =
            PathPrefs.createPersistentPreference("PPMTACSContourSmoothing", "10");

    private static final StringProperty minCollagenArea =
            PathPrefs.createPersistentPreference("PPMMinCollagenArea", "100");

    private static final StringProperty maskSmoothingSigma =
            PathPrefs.createPersistentPreference("PPMMaskSmoothingSigma", "2.0");

    // ---- Group D: classifier selection ---------------------------------------

    /** Last-used pixel-classifier name. Restored only if still in the project. */
    private static final StringProperty selectedClassifier =
            PathPrefs.createPersistentPreference("PPMSelectedClassifier", "");

    // Shared with QPSC -- same global preference key, read-only from analysis side
    private static final StringProperty activeCalibrationPath =
            PathPrefs.createPersistentPreference("PPMActiveCalibrationPath", "");

    private PPMPreferences() {}

    // =============== Analysis Parameter Accessors ===============

    public static StringProperty birefringenceThresholdProperty() {
        return birefringenceThreshold;
    }

    public static double getBirefringenceThreshold() {
        return Double.parseDouble(birefringenceThreshold.get());
    }

    public static void setBirefringenceThreshold(double threshold) {
        birefringenceThreshold.set(String.valueOf(threshold));
    }

    public static StringProperty histogramBinsProperty() {
        return histogramBins;
    }

    public static int getHistogramBins() {
        return Integer.parseInt(histogramBins.get());
    }

    public static void setHistogramBins(int bins) {
        histogramBins.set(String.valueOf(bins));
    }

    public static StringProperty saturationThresholdProperty() {
        return saturationThreshold;
    }

    public static double getSaturationThreshold() {
        return Double.parseDouble(saturationThreshold.get());
    }

    public static void setSaturationThreshold(double threshold) {
        saturationThreshold.set(String.valueOf(threshold));
    }

    public static StringProperty valueThresholdProperty() {
        return valueThreshold;
    }

    public static double getValueThreshold() {
        return Double.parseDouble(valueThreshold.get());
    }

    public static void setValueThreshold(double threshold) {
        valueThreshold.set(String.valueOf(threshold));
    }

    public static StringProperty dilationUmProperty() {
        return dilationUm;
    }

    public static double getDilationUm() {
        return Double.parseDouble(dilationUm.get());
    }

    public static void setDilationUm(double um) {
        dilationUm.set(String.valueOf(um));
    }

    public static StringProperty tacsThresholdDegProperty() {
        return tacsThresholdDeg;
    }

    public static double getTacsThresholdDeg() {
        return Double.parseDouble(tacsThresholdDeg.get());
    }

    public static void setTacsThresholdDeg(double deg) {
        tacsThresholdDeg.set(String.valueOf(deg));
    }

    public static int getMinPolylineLengthPx() {
        return Integer.parseInt(minPolylineLengthPx.get());
    }

    public static void setMinPolylineLengthPx(int px) {
        minPolylineLengthPx.set(String.valueOf(px));
    }

    public static int getMinRgbIntensity() {
        return Integer.parseInt(minRgbIntensity.get());
    }

    public static void setMinRgbIntensity(int intensity) {
        minRgbIntensity.set(String.valueOf(intensity));
    }

    public static String getPerpBoundaryClass() {
        return perpBoundaryClass.get();
    }

    public static void setPerpBoundaryClass(String className) {
        perpBoundaryClass.set(className == null ? "" : className);
    }

    public static double getBirefBlurSigma() {
        return Double.parseDouble(birefBlurSigma.get());
    }

    public static void setBirefBlurSigma(double sigma) {
        birefBlurSigma.set(String.valueOf(sigma));
    }

    public static double getHsvBlurSigma() {
        return Double.parseDouble(hsvBlurSigma.get());
    }

    public static void setHsvBlurSigma(double sigma) {
        hsvBlurSigma.set(String.valueOf(sigma));
    }

    public static String getMaskOverlayColor() {
        return maskOverlayColor.get();
    }

    public static void setMaskOverlayColor(String hex) {
        maskOverlayColor.set(hex == null ? "#00E5FF" : hex);
    }

    // ---- Group A accessors ---------------------------------------------------

    public static String getZoneMode() {
        return zoneMode.get();
    }

    public static void setZoneMode(String mode) {
        zoneMode.set(mode == null ? "outside" : mode);
    }

    public static boolean getFillHoles() {
        return Boolean.parseBoolean(fillHoles.get());
    }

    public static void setFillHoles(boolean value) {
        fillHoles.set(String.valueOf(value));
    }

    public static boolean getUseClassifier() {
        return Boolean.parseBoolean(useClassifier.get());
    }

    public static void setUseClassifier(boolean value) {
        useClassifier.set(String.valueOf(value));
    }

    // ---- Group B accessors ---------------------------------------------------

    public static boolean getExtendedTACSEnabled() {
        return Boolean.parseBoolean(extendedTacsEnabled.get());
    }

    public static void setExtendedTACSEnabled(boolean value) {
        extendedTacsEnabled.set(String.valueOf(value));
    }

    public static double getMinCollagenDensity() {
        return Double.parseDouble(minCollagenDensity.get());
    }

    public static void setMinCollagenDensity(double value) {
        minCollagenDensity.set(String.valueOf(value));
    }

    public static double getMinSignalThreshold() {
        return Double.parseDouble(minSignalThreshold.get());
    }

    public static void setMinSignalThreshold(double value) {
        minSignalThreshold.set(String.valueOf(value));
    }

    // ---- Group C accessors ---------------------------------------------------

    public static double getBoundarySmoothing() {
        return Double.parseDouble(boundarySmoothing.get());
    }

    public static void setBoundarySmoothing(double value) {
        boundarySmoothing.set(String.valueOf(value));
    }

    public static int getTACSContourSmoothing() {
        return Integer.parseInt(tacsContourSmoothing.get());
    }

    public static void setTACSContourSmoothing(int value) {
        tacsContourSmoothing.set(String.valueOf(value));
    }

    public static int getMinCollagenArea() {
        return Integer.parseInt(minCollagenArea.get());
    }

    public static void setMinCollagenArea(int value) {
        minCollagenArea.set(String.valueOf(value));
    }

    public static double getMaskSmoothingSigma() {
        return Double.parseDouble(maskSmoothingSigma.get());
    }

    public static void setMaskSmoothingSigma(double value) {
        maskSmoothingSigma.set(String.valueOf(value));
    }

    // ---- Group D accessor ----------------------------------------------------

    public static String getSelectedClassifier() {
        return selectedClassifier.get();
    }

    public static void setSelectedClassifier(String name) {
        selectedClassifier.set(name == null ? "" : name);
    }

    // =============== Active Calibration (shared with QPSC) ===============

    public static String getActiveCalibrationPath() {
        return activeCalibrationPath.get();
    }

    public static boolean hasActiveCalibration() {
        String path = getActiveCalibrationPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        return new java.io.File(path).exists();
    }
}
