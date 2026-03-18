package qupath.ext.ppm.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.ppm.service.ApposePPMService;

/**
 * Setup wizard dialog for first-time PPM analysis environment installation.
 *
 * <p>Guides the user through downloading and configuring the Python
 * environment with ppm_library and its dependencies (~500 MB).
 * Shows download warnings, environment location, and progress during installation.
 *
 * <p>Multi-state dialog: pre-setup confirmation -> in-progress -> complete or error (with retry).
 *
 * @author UW-LOCI
 * @since 0.1.2
 */
public class SetupEnvironmentDialog {

    private static final Logger logger = LoggerFactory.getLogger(SetupEnvironmentDialog.class);

    private final Stage stage;
    private final Runnable onComplete;

    // UI components shared across states
    private VBox contentBox;
    private Label statusLabel;

    /**
     * Creates a new setup dialog.
     *
     * @param owner      the owner window for modality (typically QuPath's primary stage)
     * @param onComplete callback invoked on successful setup completion (may be null)
     */
    public SetupEnvironmentDialog(Window owner, Runnable onComplete) {
        this.onComplete = onComplete;
        this.stage = new Stage();
        stage.setTitle("PPM Analysis Environment Setup");
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setResizable(false);

        buildPreSetupView();

        Scene scene = new Scene(contentBox);
        stage.setScene(scene);
    }

    /** Shows the dialog. */
    public void show() {
        stage.show();
    }

    // ==================== View States ====================

    private void buildPreSetupView() {
        contentBox = new VBox(12);
        contentBox.setPadding(new Insets(20));
        contentBox.setPrefWidth(500);

        // Title
        Label titleLabel = new Label("PPM Analysis Environment Setup");
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 14));

        // Description
        Label descLabel = new Label("This will download and install the Python environment required for "
                + "PPM analysis (polarity plots, perpendicularity, batch analysis). "
                + "The environment includes ppm_library and all scientific dependencies.");
        descLabel.setWrapText(true);

        // Download warning
        Label downloadLabel = new Label("The initial download is approximately 500 MB. "
                + "Subsequent launches will reuse the cached environment.");
        downloadLabel.setWrapText(true);

        // Metered connection warning
        Label meteredLabel = new Label("[!] Not recommended on metered connections.");
        meteredLabel.setWrapText(true);
        meteredLabel.setStyle("-fx-font-style: italic;");

        // Environment location
        Label envLocLabel = new Label("Environment location:");
        envLocLabel.setFont(Font.font(null, FontWeight.BOLD, 12));

        Label envPathLabel = new Label(ApposePPMService.getEnvironmentPath().toString());
        envPathLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        envPathLabel.setPadding(new Insets(0, 0, 0, 8));

        // Buttons
        Button beginButton = new Button("Begin Setup");
        beginButton.setDefaultButton(true);
        beginButton.setOnAction(e -> startSetup());

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonBox = new HBox(8, spacer, beginButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        contentBox
                .getChildren()
                .addAll(titleLabel, descLabel, downloadLabel, meteredLabel, envLocLabel, envPathLabel, buttonBox);
    }

    private void showInProgressView() {
        contentBox.getChildren().clear();

        Label titleLabel = new Label("PPM Analysis Environment Setup");
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 14));

        Label inProgressLabel = new Label("Installing Python environment and dependencies...");

        statusLabel = new Label("Preparing...");
        statusLabel.setWrapText(true);

        ProgressBar progressBar = new ProgressBar(-1); // indeterminate
        progressBar.setMaxWidth(Double.MAX_VALUE);

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonBox = new HBox(8, spacer, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        contentBox.getChildren().addAll(titleLabel, inProgressLabel, statusLabel, progressBar, buttonBox);
    }

    private void showCompleteView() {
        contentBox.getChildren().clear();

        Label titleLabel = new Label("PPM Analysis Environment Setup");
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 14));

        Label completeLabel = new Label("[OK] Environment setup complete!");
        completeLabel.setFont(Font.font(null, FontWeight.BOLD, 13));
        completeLabel.setStyle("-fx-text-fill: #2e7d32;");

        Label detailLabel = new Label("The PPM analysis environment is ready. You can now use:\n"
                + "  - PPM Polarity Plot\n"
                + "  - Surface Perpendicularity\n"
                + "  - Batch PPM Analysis\n\n"
                + "Use the Python Console (in the PPM menu) to monitor "
                + "Python output during analysis.");
        detailLabel.setWrapText(true);

        Button closeButton = new Button("Close");
        closeButton.setDefaultButton(true);
        closeButton.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonBox = new HBox(8, spacer, closeButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        contentBox.getChildren().addAll(titleLabel, completeLabel, detailLabel, buttonBox);
    }

    private void showErrorView(String errorMessage) {
        contentBox.getChildren().clear();

        Label titleLabel = new Label("PPM Analysis Environment Setup");
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 14));

        Label failedLabel = new Label("Environment setup failed");
        failedLabel.setFont(Font.font(null, FontWeight.BOLD, 13));
        failedLabel.setStyle("-fx-text-fill: #c62828;");

        Label errorLabel = new Label(errorMessage);
        errorLabel.setWrapText(true);
        errorLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        Button retryButton = new Button("Retry");
        retryButton.setDefaultButton(true);
        retryButton.setOnAction(e -> startSetup());

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonBox = new HBox(8, spacer, retryButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        contentBox.getChildren().addAll(titleLabel, failedLabel, errorLabel, buttonBox);
    }

    // ==================== Setup Execution ====================

    private void startSetup() {
        showInProgressView();

        Thread setupThread = new Thread(
                () -> {
                    try {
                        ApposePPMService.getInstance()
                                .initialize(status -> Platform.runLater(() -> {
                                    if (statusLabel != null) {
                                        statusLabel.setText(status);
                                    }
                                }));

                        Platform.runLater(() -> {
                            showCompleteView();
                            if (onComplete != null) {
                                onComplete.run();
                            }
                        });
                    } catch (Exception e) {
                        logger.error("Environment setup failed", e);
                        Platform.runLater(() -> showErrorView(e.getMessage()));
                    }
                },
                "PPM-EnvironmentSetup");
        setupThread.setDaemon(true);
        setupThread.start();
    }
}
