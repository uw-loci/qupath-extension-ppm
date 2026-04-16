package qupath.ext.ppm.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Discovers and groups PPM image siblings (sum, biref, angle images) within a QuPath project.
 *
 * <p>Given any PPM image, finds its siblings by matching on image_collection, sample_name,
 * and annotation_name metadata. Classifies each sibling as sum, biref, or angle image.</p>
 */
public class PPMImageSetDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(PPMImageSetDiscovery.class);

    private PPMImageSetDiscovery() {}

    /**
     * A group of related PPM images: the sum image, birefringence image,
     * individual angle images, and the calibration path.
     */
    public static class PPMAnalysisSet {
        public final ProjectImageEntry<?> sumImage;
        public final ProjectImageEntry<?> birefImage;
        public final List<ProjectImageEntry<?>> angleImages;
        public final String calibrationPath;

        public PPMAnalysisSet(
                ProjectImageEntry<?> sumImage,
                ProjectImageEntry<?> birefImage,
                List<ProjectImageEntry<?>> angleImages,
                String calibrationPath) {
            this.sumImage = sumImage;
            this.birefImage = birefImage;
            this.angleImages = angleImages != null ? angleImages : List.of();
            this.calibrationPath = calibrationPath;
        }

        public boolean hasSumImage() {
            return sumImage != null;
        }

        public boolean hasBirefImage() {
            return birefImage != null;
        }

        public boolean hasCalibration() {
            return calibrationPath != null && !calibrationPath.isEmpty();
        }
    }

    /**
     * Finds the PPM analysis set for a given image entry.
     *
     * <p>Given any PPM image (sum, biref, or angle), finds its siblings by matching
     * on image_collection, sample_name, and annotation_name. Biref images are identified
     * by "_biref" in their angle metadata or image name; sum images by "_sum".</p>
     *
     * <p>The calibration path is taken from the first image in the set that has
     * ppm_calibration metadata.</p>
     *
     * @param entry Any image entry in the PPM analysis set
     * @param project The QuPath project to search
     * @return The PPM analysis set, or null if the entry has no collection metadata
     */
    public static PPMAnalysisSet findPPMAnalysisSet(ProjectImageEntry<?> entry, Project<?> project) {
        if (entry == null || project == null) {
            return null;
        }

        int collection = ImageMetadataManager.getImageCollection(entry);
        if (collection < 0) {
            logger.warn("Image {} has no image_collection metadata", entry.getImageName());
            return null;
        }

        String sampleName = ImageMetadataManager.getSampleName(entry);
        String annotationName = entry.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);

        List<ProjectImageEntry<?>> siblings = new ArrayList<>();
        for (ProjectImageEntry<?> candidate : project.getImageList()) {
            if (ImageMetadataManager.getImageCollection(candidate) != collection) continue;

            String candSample = ImageMetadataManager.getSampleName(candidate);
            String candAnnotation = candidate.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);

            if (!Objects.equals(sampleName, candSample)) continue;
            if (!Objects.equals(annotationName, candAnnotation)) continue;

            String modality = candidate.getMetadata().get(ImageMetadataManager.MODALITY);
            if (modality == null || !modality.toLowerCase().startsWith("ppm")) continue;

            siblings.add(candidate);
        }

        ProjectImageEntry<?> sumImage = null;
        ProjectImageEntry<?> birefImage = null;
        List<ProjectImageEntry<?>> angleImages = new ArrayList<>();
        String calibrationPath = null;

        for (ProjectImageEntry<?> sibling : siblings) {
            String angle = sibling.getMetadata().get(ImageMetadataManager.ANGLE);
            String imageName = sibling.getImageName().toLowerCase();

            String siblingCalib = ImageMetadataManager.getPPMCalibration(sibling);
            if (siblingCalib != null && calibrationPath == null) {
                calibrationPath = siblingCalib;
            }

            if (isBirefImage(angle, imageName)) {
                birefImage = sibling;
            } else if (isSumImage(angle, imageName)) {
                sumImage = sibling;
            } else {
                angleImages.add(sibling);
            }
        }

        logger.debug(
                "PPM analysis set for collection {}: sum={}, biref={}, angles={}, calibration={}",
                collection,
                sumImage != null ? sumImage.getImageName() : "none",
                birefImage != null ? birefImage.getImageName() : "none",
                angleImages.size(),
                calibrationPath != null ? calibrationPath : "none");

        return new PPMAnalysisSet(sumImage, birefImage, angleImages, calibrationPath);
    }

    private static boolean isBirefImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("biref")) return true;
        return imageName.contains("biref");
    }

    private static boolean isSumImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("sum")) return true;
        return imageName.contains("_sum");
    }
}
