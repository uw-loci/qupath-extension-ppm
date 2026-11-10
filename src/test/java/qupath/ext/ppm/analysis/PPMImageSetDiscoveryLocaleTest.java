package qupath.ext.ppm.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Under a Turkish default locale "BIREF".toLowerCase() has a dotless i, so a
 * birefringence image whose angle metadata was upper case was not recognized
 * and the analysis set lost its biref image. Discovery lowers with Locale.ROOT.
 */
class PPMImageSetDiscoveryLocaleTest {

    @Test
    void upperCaseAngleTagsAreRecognizedInEveryLocale() {
        Locale previous = Locale.getDefault();
        try {
            for (Locale l : List.of(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.GERMANY)) {
                Locale.setDefault(l);
                assertThat(PPMImageSetDiscovery.isBirefImage("BIREF", "slide_001"))
                        .as("biref under " + l)
                        .isTrue();
                assertThat(PPMImageSetDiscovery.isSumImage("SUM", "slide_001"))
                        .as("sum under " + l)
                        .isTrue();
                assertThat(PPMImageSetDiscovery.isBirefImage("7.0", "slide_001"))
                        .isFalse();
            }
        } finally {
            Locale.setDefault(previous);
        }
    }
}
