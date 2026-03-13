package qupath.ext.ppm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.handler.PPMModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point for Polarized light Microscopy (PPM) modality support.
 *
 * <p>This extension registers the PPM modality handler with QPSC's ModalityRegistry
 * during installation. QPSC's SetupScope dynamically picks up menu contributions
 * from all registered handlers, so PPM calibration menus appear automatically.</p>
 *
 * <p>This extension depends on qupath-extension-qpsc for infrastructure
 * (ModalityHandler interface, ModalityRegistry, socket client, config manager).</p>
 *
 * @author Mike Nelson
 * @since 0.1.0
 */
public class SetupPPM implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(SetupPPM.class);

    private static final String EXTENSION_NAME = "PPM Extension";
    private static final String EXTENSION_DESCRIPTION = "Polarized light microscopy (PPM) modality for QuPath/QPSC. "
            + "Provides multi-angle acquisition, calibration workflows, and birefringence optimization.";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-ppm");

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return EXTENSION_REPOSITORY;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        String extVersion = GeneralTools.getPackageVersion(SetupPPM.class);
        logger.info("Installing PPM extension v{}", extVersion != null ? extVersion : "dev");
        logger.info("QuPath version: {}", GeneralTools.getVersion());

        // Register PPM modality handler -- QPSC's SetupScope dynamically
        // picks up menu contributions from all registered handlers
        ModalityRegistry.registerHandler("ppm", new PPMModalityHandler());

        logger.info("PPM modality handler registered successfully");
    }
}
