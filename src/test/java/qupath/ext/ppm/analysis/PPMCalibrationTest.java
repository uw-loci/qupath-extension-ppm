package qupath.ext.ppm.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PPMCalibration must turn hue into the same angle as ppm_library's
 * RadialCalibrationResult.hue_to_angle. The fixture is a calibration of the -7 deg
 * sunburst image from the PPM rig; the expected angles were written by ppm_library.
 */
class PPMCalibrationTest {

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(PPMCalibrationTest.class.getResource(name).toURI());
    }

    private static double angleDifference(double a, double b) {
        return ((a - b) % 180.0 + 270.0) % 180.0 - 90.0;
    }

    @Test
    void readsTheSpokeTableAndMatchesPpmLibrary() throws Exception {
        PPMCalibration cal = PPMCalibration.load(resource("sunburst_minus7_calibration.npz"));
        assertThat(cal.hasAngleTable()).isTrue();

        List<String> rows = Files.readAllLines(resource("sunburst_minus7_expected_angles.csv")).stream()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .toList();
        assertThat(rows).hasSize(41);
        for (String row : rows) {
            String[] parts = row.split(",");
            double hue = Double.parseDouble(parts[0]);
            double expected = Double.parseDouble(parts[1]);
            assertThat(angleDifference(cal.hueToAngle(hue), expected))
                    .as("hue %s", hue)
                    .isCloseTo(0.0, within(1e-6));
        }
    }

    @Test
    void floatArrayConversionMatchesTheScalarOne() throws Exception {
        PPMCalibration cal = PPMCalibration.load(resource("sunburst_minus7_calibration.npz"));
        float[] hues = {0.0f, 0.13f, 0.5f, 0.77f, 0.999f};
        float[] angles = new float[hues.length];
        cal.hueToAngle(hues, angles);
        for (int i = 0; i < hues.length; i++) {
            assertThat((double) angles[i]).isCloseTo(cal.hueToAngle(hues[i]), within(1e-4));
        }
    }

    @Test
    void angleToHueInvertsTheTable() throws IOException, URISyntaxException {
        PPMCalibration cal = PPMCalibration.load(resource("sunburst_minus7_calibration.npz"));
        for (double angle = 0.0; angle < 180.0; angle += 2.5) {
            double back = cal.hueToAngle(cal.angleToHue(angle));
            assertThat(angleDifference(back, angle)).as("angle %s", angle).isCloseTo(0.0, within(1e-3));
        }
    }

    @Test
    void withoutATableTheStraightLineIsUsed() {
        PPMCalibration cal = new PPMCalibration(-180.0, 180.0, 0.25, 0.99, null, null);
        assertThat(cal.hasAngleTable()).isFalse();
        // hue 0.5 -> shifted 0.25 -> -45 + 180 = 135
        assertThat(cal.hueToAngle(0.5)).isCloseTo(135.0, within(1e-9));
        assertThat(cal.angleToHue(135.0)).isCloseTo(0.5, within(1e-9));
    }
}
