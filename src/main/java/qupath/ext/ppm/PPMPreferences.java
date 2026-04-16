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

    private static final StringProperty histogramBins =
            PathPrefs.createPersistentPreference("PPMHistogramBins", "18");

    private static final StringProperty saturationThreshold =
            PathPrefs.createPersistentPreference("PPMSaturationThreshold", "0.2");

    private static final StringProperty valueThreshold =
            PathPrefs.createPersistentPreference("PPMValueThreshold", "0.2");

    private static final StringProperty dilationUm =
            PathPrefs.createPersistentPreference("PPMDilationUm", "50.0");

    private static final StringProperty tacsThresholdDeg =
            PathPrefs.createPersistentPreference("PPMTacsThresholdDeg", "30.0");

    private static final StringProperty minPolylineLengthPx =
            PathPrefs.createPersistentPreference("PPMMinPolylineLengthPx", "20");

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
