package qupath.ext.ppm;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.analysis.PPMBackPropagationWorkflow;
import qupath.ext.ppm.analysis.PPMBatchAnalysisWorkflow;
import qupath.ext.ppm.analysis.PPMHueRangeWorkflow;
import qupath.ext.ppm.analysis.PPMPerpendicularityWorkflow;
import qupath.ext.ppm.analysis.PPMPolarityPlotWorkflow;
import qupath.ext.ppm.service.ApposePPMService;
import qupath.ext.ppm.ui.PythonConsoleWindow;
import qupath.ext.ppm.ui.SetupEnvironmentDialog;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point for PPM image analysis tools.
 *
 * <p>This extension provides analysis-only functionality: hue range filtering,
 * polarity plots, surface perpendicularity, batch analysis, and annotation
 * back-propagation. It does NOT register a modality handler -- QPSC owns the
 * PPM hardware handler (rotation, calibration, acquisition).</p>
 *
 * <p>Analysis menus are added under Extensions > PPM Analysis so they are
 * always available, even on workstations without microscope hardware.</p>
 */
public class SetupPPM implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(SetupPPM.class);

    private static final String EXTENSION_NAME = "PPM Analysis Extension";
    private static final String EXTENSION_DESCRIPTION =
            "Polarized light microscopy (PPM) image analysis for QuPath. "
                    + "Provides hue range filtering, polarity plots, surface perpendicularity, "
                    + "and batch analysis tools for PPM images.";
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
        logger.info("Installing PPM Analysis extension v{}", extVersion != null ? extVersion : "dev");

        // Add analysis menus under Extensions > PPM Analysis
        Menu extensionsMenu = qupath.getMenu("Extensions", true);
        Menu ppmMenu = new Menu("PPM Analysis");

        ppmMenu.getItems().addAll(
                createMenuItem("PPM Hue Range Filter...", PPMHueRangeWorkflow::run),
                createMenuItem("PPM Polarity Plot...", PPMPolarityPlotWorkflow::run),
                createMenuItem("Surface Perpendicularity...", PPMPerpendicularityWorkflow::run),
                createMenuItem("Batch PPM Analysis...", PPMBatchAnalysisWorkflow::run),
                createMenuItem("Back-Propagate Annotations...", PPMBackPropagationWorkflow::run),
                new SeparatorMenuItem(),
                createMenuItem("PPM Analysis Environment...", SetupPPM::showManageEnvironmentDialog),
                createMenuItem("Python Console...", () -> PythonConsoleWindow.getInstance().show()));

        extensionsMenu.getItems().add(ppmMenu);

        logger.info("PPM Analysis menus registered under Extensions > PPM Analysis");
    }

    private static MenuItem createMenuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private static void showManageEnvironmentDialog() {
        ApposePPMService service = ApposePPMService.getInstance();
        QuPathGUI gui = QuPathGUI.getInstance();
        javafx.stage.Window owner = gui != null ? gui.getStage() : null;

        if (!ApposePPMService.isEnvironmentBuilt()) {
            SetupEnvironmentDialog dialog = new SetupEnvironmentDialog(owner, null);
            dialog.show();
            return;
        }

        String installed = service.getInstalledPpmVersion();
        String required = ApposePPMService.getRequiredPpmVersion();
        boolean compatible = service.isVersionCompatible();
        boolean available = service.isAvailable();

        StringBuilder msg = new StringBuilder();
        msg.append("Environment: ")
                .append(ApposePPMService.getEnvironmentPath())
                .append("\n\n");
        if (available) {
            msg.append("ppm_library version: ")
                    .append(installed != null ? installed : "unknown")
                    .append("\n");
            msg.append("Required version: >= ").append(required).append("\n");
            msg.append("Status: ")
                    .append(compatible ? "Ready" : "OUTDATED - rebuild recommended")
                    .append("\n");
        } else {
            msg.append("Status: Environment exists but service is not initialized.\n");
        }
        msg.append("\nRebuild will delete and reinstall the environment (~500 MB download).\n");
        msg.append("This is needed when ppm_library has been updated.");

        boolean rebuild = qupath.fx.dialogs.Dialogs.showConfirmDialog("PPM Analysis Environment", msg.toString());
        if (!rebuild) return;

        try {
            service.shutdown();
            service.deleteEnvironment();
        } catch (Exception e) {
            logger.error("Failed to delete environment: {}", e.getMessage(), e);
            qupath.fx.dialogs.Dialogs.showErrorMessage(
                    "PPM Analysis Environment", "Failed to delete environment: " + e.getMessage());
            return;
        }

        SetupEnvironmentDialog dialog = new SetupEnvironmentDialog(owner, null);
        dialog.show();
    }
}
