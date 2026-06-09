package qupath.ext.ppm.analysis;

import java.awt.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorMaps;
import qupath.lib.color.ColorMaps.ColorMap;

/**
 * Builds and installs a QuPath measurement-map {@link ColorMap} that reproduces the
 * PPM hue wheel, so detection measurements holding a fiber angle (0-180 deg) -- such
 * as the "Window mean angle (deg)" written by the dominant-orientation analysis -- can
 * be colored with the same hues seen in the source PPM image instead of an arbitrary
 * perceptual ramp (Viridis, etc.).
 *
 * <p>The colors are derived from the active hue-to-angle calibration: for each sampled
 * angle the calibration's inverse transform ({@link PPMCalibration#angleToHue(double)})
 * gives the hue that the PPM image would show for that orientation, which is then
 * rendered as a fully saturated HSV color. Because the calibration wraps hue once
 * across the 0-180 deg axial range, the resulting map is cyclic (the 0 deg and 180 deg
 * ends meet), matching the periodicity of fiber orientation.</p>
 *
 * <p>The map is named "PPM Hue to Angle (&lt;angle&gt; deg)" using the acquisition angle
 * recorded in the image metadata, so the user can tell which image/calibration it
 * corresponds to in the Measurement Maps dropdown. For the colors to line up with true
 * fiber angles, set the measurement map's display range to 0-180.</p>
 *
 * @author Mike Nelson
 * @since 1.3.4
 */
public final class PPMHueColorMap {

    private static final Logger logger = LoggerFactory.getLogger(PPMHueColorMap.class);

    /** Number of color samples across the 0-180 deg axial angle range. */
    private static final int N_SAMPLES = 256;

    private PPMHueColorMap() {}

    /**
     * Builds (but does not install) a cyclic PPM-hue {@link ColorMap} from a calibration.
     *
     * @param calibration the active hue-to-angle calibration
     * @param name the colormap name shown in the Measurement Maps dropdown
     * @return the colormap
     */
    public static ColorMap build(PPMCalibration calibration, String name) {
        double[] r = new double[N_SAMPLES];
        double[] g = new double[N_SAMPLES];
        double[] b = new double[N_SAMPLES];
        for (int i = 0; i < N_SAMPLES; i++) {
            double angle = 180.0 * i / (N_SAMPLES - 1);
            double hue = calibration.angleToHue(angle);
            int rgb = Color.HSBtoRGB((float) hue, 1f, 1f);
            r[i] = ((rgb >> 16) & 0xFF) / 255.0;
            g[i] = ((rgb >> 8) & 0xFF) / 255.0;
            b[i] = (rgb & 0xFF) / 255.0;
        }
        return ColorMaps.createColorMap(name, r, g, b);
    }

    /**
     * Builds and installs a cyclic PPM-hue {@link ColorMap} so it appears in QuPath's
     * Measurement Maps dropdown. Installing under an existing name replaces it, so the
     * map stays current when the analysis is re-run with a different calibration or angle.
     *
     * <p>Note: the Measurement Maps pane reads the available colormaps when it opens. If
     * the pane is already open, close and reopen it (or reopen the dropdown) to see the
     * newly installed map.</p>
     *
     * @param calibration the active hue-to-angle calibration
     * @param angleLabel the acquisition-angle label (e.g. "-7 deg"); may be null/blank
     * @return the name under which the colormap was installed
     */
    public static String install(PPMCalibration calibration, String angleLabel) {
        String name = angleLabel == null || angleLabel.isBlank()
                ? "PPM Hue to Angle"
                : "PPM Hue to Angle (" + angleLabel + ")";
        ColorMap map = build(calibration, name);
        ColorMaps.installColorMaps(map);
        logger.info("Installed measurement-map colormap '{}' from calibration {}", name, calibration.getSourcePath());
        return name;
    }
}
