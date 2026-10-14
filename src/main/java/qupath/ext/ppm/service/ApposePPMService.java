package qupath.ext.ppm.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

/**
 * Singleton managing the Appose Environment and Python Service lifecycle
 * for PPM analysis (ppm_library).
 *
 * <p>Provides an embedded Python runtime for PPM fiber analysis via
 * Appose's shared-memory IPC. The Python worker is a single long-lived
 * subprocess -- globals set in {@code init()} persist across task calls,
 * enabling fast subsequent analysis without per-request overhead.</p>
 *
 * <p>The Appose environment is built from a {@code pixi.toml} bundled in the
 * JAR resources. First-time setup downloads Python and dependencies (~500 MB).
 * Subsequent launches reuse the cached environment.</p>
 *
 * <p>The generic plumbing (manifest staging, pixi, worker shutdown, failure
 * classification, the thread-death retry) lives in the shared copies
 * {@link ApposeSupport} and {@link ApposeTaskRunner}; this class holds only
 * what is specific to the PPM environment.</p>
 *
 * @author Mike Nelson
 * @since 0.2.0
 */
public class ApposePPMService {

    private static final Logger logger = LoggerFactory.getLogger(ApposePPMService.class);

    private static final String TITLE = "PPM Analysis";
    private static final String RESOURCE_BASE = "qupath/ext/ppm/";
    private static final String PIXI_TOML_RESOURCE = RESOURCE_BASE + "pixi.toml";
    // Bundled lockfile pinning the full transitive dependency tree. Installed
    // with --frozen so updates install the exact tested versions instead of
    // re-resolving against current conda-forge/PyPI. Regenerate via
    // tools/regen-pixi-lock.sh when pixi.toml changes.
    private static final String PIXI_LOCK_RESOURCE = RESOURCE_BASE + "pixi.lock";
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";
    private static final String ENV_NAME = "ppm-analysis";

    /**
     * pip install URL for ppm-library from GitHub.
     * Uses archive tarball so git is not required on the user's machine.
     */
    private static final String PPM_LIBRARY_PIP_URL =
            "ppm-library @ https://github.com/uw-loci/" + "ppm_library/archive/refs/heads/main.tar.gz";

    /**
     * pip install URL for microscope-imageprocessing from GitHub.
     * Required transitive dependency of ppm_library (>= 1.3.2). It is not
     * published to PyPI, so we install it explicitly from the GitHub tarball
     * before ppm_library. Must be installed with --no-deps for the same reason
     * as ppm_library: its Python deps are already satisfied by the pixi env.
     */
    private static final String MICROSCOPE_IMAGEPROCESSING_PIP_URL =
            "microscope-imageprocessing @ https://github.com/uw-loci/"
                    + "microscope_imageprocessing/archive/refs/heads/main.tar.gz";

    /**
     * Minimum required ppm-library version for this extension version.
     * <p>IMPORTANT: Update this constant whenever ppm_library changes
     * affect the extension (new parameters, API changes, bug fixes).
     * Every initialize() runs "pip install --upgrade" for ppm_library, so an
     * online session picks up a newer GitHub main automatically; this constant
     * is the floor the result is then checked against.</p>
     */
    private static final String REQUIRED_PPM_VERSION = "1.3.5";

    private static ApposePPMService instance;

    private Environment environment;
    // Read outside the synchronized methods by runTask()/isAvailable(); a
    // restart swaps the reference, so readers must not see a stale one.
    private volatile Service pythonService;
    private volatile boolean initialized;
    private boolean versionCompatible;
    private String installedPpmVersion;
    private volatile String initError;
    private Thread shutdownHook;

    /**
     * Why the most recent package install failed, or null if every install step
     * succeeded. Lets the version check say whether an outdated ppm_library is a
     * download failure or an upstream branch that simply has not been bumped.
     */
    private String lastInstallFailure;

    /** How {@link ApposeTaskRunner} reaches the worker and replaces a stale one. */
    private final ApposeTaskRunner.WorkerSource workerSource = new ApposeTaskRunner.WorkerSource() {
        @Override
        public Service worker() throws IOException {
            ensureInitialized();
            Service service = pythonService;
            if (service == null) {
                // A concurrent shutdown() or restartWorker() cleared it after
                // ensureInitialized() passed.
                throw new IOException(TITLE + " task cannot run: the Python worker went away");
            }
            return service;
        }

        @Override
        public void replaceWorker(Service stale) throws IOException {
            restartWorker(stale);
        }
    };

    private ApposePPMService() {}

    /**
     * Gets the singleton instance.
     */
    public static synchronized ApposePPMService getInstance() {
        if (instance == null) {
            instance = new ApposePPMService();
        }
        return instance;
    }

    /**
     * Checks if the Appose pixi environment appears to be built on disk.
     * Fast filesystem check -- does NOT trigger any downloads.
     */
    public static boolean isEnvironmentBuilt() {
        return ApposeEnvLocation.isBuilt(getEnvironmentPath());
    }

    /**
     * Returns whether the installed ppm_library version is compatible with this extension.
     * Returns false if the environment hasn't been initialized yet, or if the version is too old.
     */
    public boolean isVersionCompatible() {
        return initialized && versionCompatible;
    }

    /**
     * Returns the installed ppm_library version, or null if not yet detected.
     */
    public String getInstalledPpmVersion() {
        return installedPpmVersion;
    }

    /**
     * Returns the minimum required ppm_library version for this extension.
     */
    public static String getRequiredPpmVersion() {
        return REQUIRED_PPM_VERSION;
    }

    /**
     * Returns the path where the Appose pixi environment is stored.
     */
    public static Path getEnvironmentPath() {
        ApposePPMService svc = instance;
        if (svc != null && svc.environment != null) {
            return Path.of(svc.environment.base());
        }
        return ApposeEnvLocation.resolve(qupath.ext.ppm.PPMPreferences.getEnvBaseDir(), ENV_NAME);
    }

    /**
     * Builds the pixi environment and starts the Python service.
     * Slow the first time (downloads dependencies), instant on subsequent runs.
     *
     * @param statusCallback optional callback for progress messages (may be null)
     * @throws IOException if resource loading or environment build fails
     */
    public synchronized void initialize(Consumer<String> statusCallback) throws IOException {
        if (initialized) {
            // A previously-good session can still have lost its Python worker
            // (Appose "thread death", an OS kill, an out-of-memory subprocess).
            // Short-circuiting on the flag alone left every later task failing
            // for the rest of the QuPath session, because callers re-check
            // isAvailable() -- which was true -- and never re-initialized.
            if (isWorkerAlive()) {
                report(statusCallback, "Already initialized");
                return;
            }
            logger.warn("PPM Python worker is no longer alive; restarting it (the pixi env is already built).");
            report(statusCallback, "Restarting Python worker...");
            restartWorker();
            report(statusCallback, "Python worker restarted");
            return;
        }

        long setupStart = System.nanoTime();
        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing PPM Appose environment...");

            String pixiToml = ApposeSupport.loadResource(PIXI_TOML_RESOURCE);
            String pixiLock = ApposeSupport.loadResource(PIXI_LOCK_RESOURCE);

            // ALL Appose operations require the extension classloader as TCCL.
            // Appose and its dependencies use ServiceLoader internally.
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());

            try {
                // Sync manifest + lock; the lock is staged so the install below
                // can run --frozen (exact pinned versions, no re-resolution).
                ApposeSupport.syncManifest(getEnvironmentPath(), pixiToml, pixiLock, logger);

                report(statusCallback, "Building pixi environment (this may take several minutes)...");

                // Build the pixi environment (downloads deps on first run)
                var envBuilder = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
                        .logDebug();

                // Builder.base() overrides name(), so pass the FULL
                // <base>/ENV_NAME directory -- the same path
                // getEnvironmentPath() reports and the manifest was staged into.
                Path configuredDir = configuredEnvDir();
                if (configuredDir != null) {
                    Files.createDirectories(configuredDir);
                    envBuilder.base(configuredDir.toFile());
                    logger.info("Building the environment at the configured location: {}", configuredDir);
                }
                long buildStart = System.nanoTime();
                environment = envBuilder.build();
                logger.info(
                        "Appose environment configured at {} ({} ms)",
                        environment.base(),
                        ApposeSupport.elapsedMs(buildStart));

                // Install dependencies via pixi, then ppm-library via pip.
                // Appose's build() with .content() only writes pixi.toml -- it does
                // NOT run pixi install. We must do that explicitly.
                installPPMLibrary(statusCallback);

                report(statusCallback, "Starting Python service...");

                // Create Python service (lazy - subprocess starts on first task)
                pythonService = spawnWorker();

                // Force Python subprocess to start and verify packages
                report(statusCallback, "Verifying installed packages...");
                logger.info("Running PPM environment verification task...");
                long workerStart = System.nanoTime();

                String verifyScript = "import ppm_library\n"
                        + "import scipy\n"
                        + "import skimage\n"
                        + "task.outputs['ppm_version'] = getattr(ppm_library, '__version__', 'unknown')\n"
                        + "task.outputs['init_error'] = str(init_error) if init_error else ''\n";

                Task verifyTask = pythonService.task(verifyScript);
                verifyTask.listen(event -> {
                    if (event.responseType == ResponseType.FAILURE || event.responseType == ResponseType.CRASH) {
                        logger.error("Verification task failed: {}", verifyTask.error);
                    }
                });
                verifyTask.waitFor();

                String ppmVersion = stringOutput(verifyTask, "ppm_version");
                String pythonInitError = stringOutput(verifyTask, "init_error");

                if (pythonInitError != null && !pythonInitError.isEmpty()) {
                    throw new IOException("Python init failed: " + pythonInitError);
                }

                installedPpmVersion = ppmVersion;
                logger.info(
                        "PPM environment verified: ppm_library {}; the Python worker started in {} ms",
                        ppmVersion,
                        ApposeSupport.elapsedMs(workerStart));

                // Check whether the installed version meets the minimum requirement.
                // There is deliberately no retry here: installPPMLibrary() already
                // ran "pip install --upgrade" moments ago in this same call, so
                // re-running the identical command cannot change the answer. What
                // matters instead is saying WHY the version is still too old.
                if (!isVersionSufficient(ppmVersion, REQUIRED_PPM_VERSION)) {
                    logger.error(
                            "ppm_library {} does not meet the required {} floor", ppmVersion, REQUIRED_PPM_VERSION);
                    versionCompatible = false;
                    initError = "ppm_library " + ppmVersion + " is outdated. Required: " + REQUIRED_PPM_VERSION + "+. "
                            + (lastInstallFailure != null
                                    ? "The upgrade could not be installed: " + lastInstallFailure
                                    : "The upgrade installed without error, so the GitHub main branch may not "
                                            + "carry " + REQUIRED_PPM_VERSION + " yet.");
                } else {
                    versionCompatible = true;
                }

                String extVersion = GeneralTools.getPackageVersion(ApposePPMService.class);
                logger.info("=== PPM Analysis Environment ===");
                logger.info("  Extension version: {}", extVersion != null ? extVersion : "dev");
                logger.info("  ppm_library version: {}", ppmVersion);
                logger.info("  Required ppm_library: >= {}", REQUIRED_PPM_VERSION);
                logger.info("  Version compatible: {}", versionCompatible);
                logger.info("  Environment path: {}", getEnvironmentPath());
                logger.info("  Setup time: {} ms", ApposeSupport.elapsedMs(setupStart));
                logger.info("=================================");

                initialized = true;
                // Built AND verified: the only point at which offering to
                // remove the environment this replaced is safe.
                offerPreviousEnvCleanup();
                initError = versionCompatible ? null : initError;
                registerShutdownHook();
                report(
                        statusCallback,
                        versionCompatible
                                ? "Setup complete! (ppm_library " + ppmVersion + ")"
                                : "WARNING: ppm_library " + ppmVersion + " is outdated (need " + REQUIRED_PPM_VERSION
                                        + "+)");
                logger.info("PPM Appose Python service initialized");

            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }

        } catch (Exception e) {
            // Match failure signatures against the full cause chain -- pixi's
            // BuildException only says "pixi build failed" at the top; the
            // actionable Windows file-lock text lives in a nested cause.
            String fullMsg = ApposeSupport.collectCauseMessages(e);
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize PPM Appose: {}", e.getMessage(), e);
            if (ApposeSupport.looksLikeWindowsFileLock(fullMsg)) {
                logger.warn(
                        "Pixi env install hit a Windows file lock; manual recovery required\n{}",
                        ApposeSupport.windowsFileLockAdvice(getEnvironmentPath()));
                report(statusCallback, "Pixi env install failed: Windows file lock. See recovery steps.");
                try {
                    qupath.fx.dialogs.Dialogs.showWarningNotification(
                            TITLE,
                            "Pixi env install failed: a file was locked by another process. Close QuPath,"
                                    + " delete the .pixi folder, and relaunch. See the log for full recovery steps.");
                } catch (Exception fxEx) {
                    // FX not running; log advice already emitted.
                }
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Runs a named task script with the given inputs, recovering once from a
     * stale Appose worker.
     *
     * <p>Every PPM script emits an UPDATE as soon as its imports succeed, so a
     * "thread death" with no UPDATE seen is a stale worker and the task is
     * retried on a fresh one; a failure after that point is a real failure.
     * The rules are in {@link ApposeTaskRunner}.</p>
     *
     * @param scriptName script name without .py extension
     * @param inputs     map of input values passed to the script
     * @return the completed Task with outputs
     * @throws IOException if the service is not available or the task fails
     */
    public Task runTask(String scriptName, Map<String, Object> inputs) throws IOException {
        String script;
        try {
            script = loadScript(scriptName + ".py");
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }
        return ApposeTaskRunner.run(
                "PPM", workerSource, scriptName, script, inputs, new ApposeTaskRunner.Options().maxAttempts(2));
    }

    /**
     * Creates the Python worker and queues its init script. Does NOT start the
     * subprocess; Appose does that on the first task.
     *
     * <p>Caller must hold the monitor and have the extension classloader set
     * as TCCL. {@code import numpy} must stay the literal first line of the
     * init string to avoid a Windows deadlock (numpy/numpy#24290).
     */
    private Service spawnWorker() throws IOException {
        Service fresh = environment.python();
        fresh.debug(msg -> {
            logger.info("[PPM Python] {}", msg);
            qupath.ext.ppm.ui.PythonConsoleWindow.appendMessage(msg);
        });
        fresh.init("import numpy\n" + loadScript("init_ppm.py"));
        return fresh;
    }

    /**
     * Recreates ONLY the Python worker subprocess, killing any stale or zombie
     * worker first. The pixi environment is untouched -- it is already built, so
     * this just spins up a fresh subprocess and re-runs {@code init_ppm.py}.
     *
     * @throws IOException if the environment is not built or the worker cannot
     *                     be recreated
     */
    public synchronized void restartWorker() throws IOException {
        restartWorker(null);
    }

    /**
     * {@link #restartWorker()}, but a no-op when another thread already
     * replaced the worker that {@code staleWorker} refers to, so two tasks
     * failing on the same stale worker do not tear down each other's
     * replacement.
     *
     * @param staleWorker the Service the failed task was submitted to, or
     *                    {@code null} to restart unconditionally
     */
    public synchronized void restartWorker(Service staleWorker) throws IOException {
        if (environment == null) {
            throw new IOException("Cannot restart worker: Appose environment not built");
        }
        if (staleWorker != null && pythonService != staleWorker) {
            logger.info("PPM Python worker was already replaced by another thread; skipping redundant restart");
            return;
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            long start = System.nanoTime();
            Service stale = pythonService;
            pythonService = null;
            ApposeSupport.stopWorker(stale, 3000, logger);

            Service fresh = spawnWorker();
            // Appose starts the subprocess lazily, and a not-yet-started worker
            // reports isAlive() == false -- which isAvailable() would read as
            // dead. Start it here so the service is honestly available on return.
            fresh.start();
            pythonService = fresh;
            logger.info(
                    "PPM Appose worker restarted in {} ms (fresh Python subprocess)", ApposeSupport.elapsedMs(start));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to restart PPM Appose worker: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Gracefully shuts down the Python service.
     * Closes stdin first, then force-kills if needed after 5 seconds.
     */
    public synchronized void shutdown() {
        Service service = pythonService;
        if (service != null) {
            logger.info("Shutting down PPM Python service...");
            ApposeSupport.stopWorker(service, 5000, logger);
            pythonService = null;
        }
        initialized = false;
        removeShutdownHook();
        logger.info("PPM Appose service shut down");
    }

    /**
     * Deletes the Appose pixi environment from disk.
     * Service must be shut down first via {@link #shutdown()}.
     *
     * @throws IOException if the environment directory cannot be deleted
     */
    public synchronized void deleteEnvironment() throws IOException {
        if (pythonService != null) {
            throw new IOException(
                    "Cannot delete environment while Python service is running. " + "Call shutdown() first.");
        }
        if (environment != null) {
            try {
                logger.info("Deleting PPM environment via API: {}", environment.base());
                environment.delete();
                environment = null;
                logger.info("PPM environment deleted");
                return;
            } catch (Exception e) {
                logger.warn("environment.delete() failed, falling back to manual deletion: {}", e.getMessage());
                environment = null;
            }
        }
        Path envPath = getEnvironmentPath();
        if (Files.exists(envPath)) {
            logger.info("Deleting environment directory: {}", envPath);
            ApposeSupport.deleteDirectoryRecursively(envPath);
            logger.info("Environment directory deleted");
        }
    }

    /**
     * Checks whether the service is initialized and available.
     *
     * <p>Includes a liveness check on the Python worker. Without it a worker
     * that died mid-session still reported "available", so callers that guard
     * re-initialization with {@code if (!isAvailable())} never recovered and
     * every remaining task in the session failed.</p>
     */
    public boolean isAvailable() {
        return initialized && initError == null && isWorkerAlive();
    }

    /**
     * True when a Python worker subprocess exists and is still running.
     *
     * <p>Appose launches the subprocess lazily, so a worker that has been
     * created but never run anything also reports not-alive. Both
     * {@link #initialize(Consumer)} (via its verification task) and
     * {@link #restartWorker()} force the subprocess to start before returning,
     * so for callers this reduces to "the worker died".</p>
     */
    private boolean isWorkerAlive() {
        Service service = pythonService;
        return service != null && service.isAlive();
    }

    /**
     * Gets the initialization error message, if any.
     */
    public String getInitError() {
        return initError;
    }

    /**
     * Executes a callable with the TCCL set to the extension's classloader.
     */
    public static <T> T withExtensionClassLoader(java.util.concurrent.Callable<T> callable) throws Exception {
        return ApposeSupport.withExtensionClassLoader(callable);
    }

    // ==================== Internal Helpers ====================

    /**
     * Installs pixi dependencies, then the Python packages that are not on PyPI
     * (microscope_imageprocessing and ppm_library) via pip. Order matters:
     * microscope_imageprocessing must be installed first because ppm_library
     * imports it at module load time.
     */
    private void installPPMLibrary(Consumer<String> statusCallback) throws IOException {
        lastInstallFailure = null;

        Path envBase = Path.of(environment.base());
        Path manifestPath = envBase.resolve("pixi.toml");

        Path pixi = ApposeSupport.findPixiBinary();
        if (pixi == null) {
            throw new IOException("Cannot find pixi binary. "
                    + "The Appose environment may not have been set up correctly. "
                    + "Try PPM > Rebuild PPM Analysis Environment.");
        }

        // Both pip installs below pull from a GitHub tarball, so initialize()
        // used to need live internet EVERY session -- an air-gapped microscope
        // workstation or a GitHub outage made all four PPM menu items unusable
        // even with the ~500 MB environment fully built. So: find out what is
        // already installed first. If it already satisfies the floor, the whole
        // install path becomes best-effort and PPM analysis runs offline.
        String preinstalled = detectInstalledPpmVersion(pixi, envBase, manifestPath);
        boolean alreadySatisfied = isVersionSufficient(preinstalled, REQUIRED_PPM_VERSION);
        if (alreadySatisfied) {
            logger.info(
                    "ppm_library {} is already installed and meets the {} floor; "
                            + "the install steps below are best-effort this session.",
                    preinstalled,
                    REQUIRED_PPM_VERSION);
        }

        // Install conda dependencies strictly from the bundled lockfile:
        // --frozen installs the exact pinned versions and never re-resolves.
        logger.info("Running pixi install --frozen from the bundled lock...");
        report(statusCallback, "Installing Python dependencies (this may take several minutes on first run)...");
        try {
            long start = System.nanoTime();
            ApposeSupport.runPixiCommand(
                    pixi, envBase, manifestPath, line -> logger.info("[pixi] {}", line), "install", "--frozen");
            logger.info("pixi install --frozen finished in {} ms", ApposeSupport.elapsedMs(start));
        } catch (IOException e) {
            recordInstallFailure("pixi install", e, alreadySatisfied, preinstalled, statusCallback);
        }

        // ppm_library >= 1.3.2 imports microscope_imageprocessing at module
        // load time, so it must be installed first. Neither package is on
        // PyPI, so both are pulled directly from GitHub tarballs.
        try {
            pipInstallFromUrl(
                    pixi,
                    envBase,
                    manifestPath,
                    "microscope-imageprocessing",
                    MICROSCOPE_IMAGEPROCESSING_PIP_URL,
                    statusCallback);
        } catch (IOException e) {
            recordInstallFailure(
                    "microscope-imageprocessing install", e, alreadySatisfied, preinstalled, statusCallback);
        }

        try {
            pipInstallFromUrl(pixi, envBase, manifestPath, "ppm-library", PPM_LIBRARY_PIP_URL, statusCallback);
        } catch (IOException e) {
            recordInstallFailure("ppm-library install", e, alreadySatisfied, preinstalled, statusCallback);
        }
    }

    /**
     * Handles a failed install step: rethrows when the environment cannot run
     * without it, and warns-and-continues when a satisfactory ppm_library is
     * already installed (the offline-reanalysis case).
     *
     * @param step             short label for the step that failed
     * @param cause            the failure
     * @param alreadySatisfied whether the installed ppm_library already meets
     *                         {@link #REQUIRED_PPM_VERSION}
     * @param preinstalled     the already-installed ppm_library version, if any
     * @throws IOException when the failure is fatal
     */
    private void recordInstallFailure(
            String step,
            IOException cause,
            boolean alreadySatisfied,
            String preinstalled,
            Consumer<String> statusCallback)
            throws IOException {
        // Record it either way -- an outdated ppm_library at the end of
        // initialize() needs to report the download failure as the reason.
        lastInstallFailure = step + " failed: " + cause.getMessage();

        if (!alreadySatisfied) {
            throw cause;
        }
        logger.warn(
                "{} failed, continuing with the environment already on disk "
                        + "(ppm_library {} meets the {} floor). PPM analysis will run offline. Cause: {}",
                step,
                preinstalled,
                REQUIRED_PPM_VERSION,
                cause.getMessage());
        report(statusCallback, "Offline: using the Python environment already installed.");
    }

    /**
     * Reports the ppm_library version already installed in the pixi environment,
     * or null when nothing usable is installed (env not built, import fails,
     * pixi unable to run). Both packages are imported, because ppm_library
     * imports microscope_imageprocessing at module load time -- a ppm_library
     * that cannot import is not usable, however new its version string.
     */
    private String detectInstalledPpmVersion(Path pixi, Path envBase, Path manifestPath) {
        // Probe only when the pixi env already has an interpreter. "pixi run"
        // installs the env if it is missing, so probing an unbuilt env would
        // silently move the multi-minute first-run install into a step the
        // status callback reports as nothing at all.
        Path envDir = envBase.resolve(".pixi").resolve("envs").resolve("default");
        if (!Files.isRegularFile(envDir.resolve("bin").resolve("python"))
                && !Files.isRegularFile(envDir.resolve("python.exe"))) {
            return null; // environment not installed yet
        }
        String marker = "PPM_INSTALLED_VERSION=";
        java.util.List<String> command = java.util.List.of(
                pixi.toString(),
                "run",
                "--frozen",
                "--manifest-path",
                manifestPath.toString(),
                "python",
                "-c",
                "import ppm_library, microscope_imageprocessing; " + "print('" + marker
                        + "' + getattr(ppm_library, '__version__', 'unknown'))");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(envBase.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String version = null;
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf(marker);
                    if (idx >= 0) {
                        version = line.substring(idx + marker.length()).trim();
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.info("No usable ppm_library detected in the environment (probe exit code {})", exitCode);
                return null;
            }
            return version;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            logger.info("Could not probe the installed ppm_library version: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Runs {@code pixi run pip install --upgrade --no-deps <url>} for a single
     * package and streams the output to the logger. {@code --no-deps} is used
     * because the Python dependencies of these packages are already satisfied
     * by the pixi environment; letting pip re-resolve them would fight with
     * the conda-managed versions.
     */
    private void pipInstallFromUrl(
            Path pixi,
            Path envBase,
            Path manifestPath,
            String packageLabel,
            String packageUrl,
            Consumer<String> statusCallback)
            throws IOException {
        logger.info("Installing {} via pixi run pip...", packageLabel);
        report(statusCallback, "Installing " + packageLabel + " package...");

        java.util.List<String> command = java.util.List.of(
                pixi.toString(),
                "run",
                "--manifest-path",
                manifestPath.toString(),
                "pip",
                "install",
                "--upgrade",
                "--no-deps",
                packageUrl);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(envBase.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("[pip] {}", line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pip install " + packageLabel + " interrupted", e);
        }

        if (exitCode != 0) {
            throw new IOException("pip install " + packageLabel + " failed (exit code " + exitCode + "):\n" + output);
        }
        logger.info("{} installed successfully", packageLabel);
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = ApposeSupport.registerShutdownHook("PPMAnalysis-ShutdownHook", () -> pythonService, logger);
    }

    private void removeShutdownHook() {
        ApposeSupport.removeShutdownHook(shutdownHook);
        shutdownHook = null;
    }

    private void ensureInitialized() throws IOException {
        if (isAvailable()) {
            return;
        }
        // A healthy session whose worker died is recoverable without rebuilding
        // the ~500 MB environment -- restart just the subprocess. Only a session
        // that never initialized, or one carrying a real init error, is fatal.
        if (initialized && initError == null && environment != null) {
            logger.warn("PPM Python worker is no longer alive; restarting it before running the task.");
            restartWorker();
            if (isAvailable()) {
                return;
            }
        }
        throw new IOException("PPM Appose service is not available" + (initError != null ? ": " + initError : ""));
    }

    String loadScript(String scriptFileName) throws IOException {
        return ApposeSupport.loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    /**
     * Reads a String output from a completed task, mapping an absent key to
     * null. {@code String.valueOf()} would turn an absent key into the literal
     * "null", which then reads downstream as a real value -- a bogus Python
     * init error, or a version string that no comparison can make sense of.
     */
    private static String stringOutput(Task task, String key) {
        Object value = task.outputs.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Compares two semantic version strings (e.g., "1.3.0" vs "1.3.1").
     * Returns true if {@code installed} is >= {@code required}.
     */
    static boolean isVersionSufficient(String installed, String required) {
        if (installed == null || installed.isEmpty() || "unknown".equals(installed) || "null".equals(installed)) {
            return false;
        }
        try {
            int[] inst = parseVersion(installed);
            int[] req = parseVersion(required);
            for (int i = 0; i < Math.max(inst.length, req.length); i++) {
                int a = i < inst.length ? inst[i] : 0;
                int b = i < req.length ? req[i] : 0;
                if (a > b) return true;
                if (a < b) return false;
            }
            return true; // equal
        } catch (NumberFormatException e) {
            logger.warn("Could not parse version string: installed='{}', required='{}'", installed, required);
            return false;
        }
    }

    private static int[] parseVersion(String version) {
        // Strip any suffix like ".dev0", "-rc1", etc.
        String clean = version.replaceAll("[^0-9.].*", "");
        String[] parts = clean.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }

    /**
     * The configured environment directory, or null to use the Appose default.
     * Kept beside the path-reporting method so the reported location and the
     * built location cannot drift apart: setting only one moves where we SAY
     * the environment is while Appose keeps building elsewhere, and the staged
     * manifest is never read.
     */
    private static Path configuredEnvDir() {
        String base = qupath.ext.ppm.PPMPreferences.getEnvBaseDir();
        if (base == null || base.isBlank()) {
            return null;
        }
        return ApposeEnvLocation.resolve(base, ENV_NAME);
    }

    /**
     * After a VERIFIED build, offer to remove the environment this one replaced.
     *
     * <p>Off the initialization thread and never blocking it; degrades to
     * leaving the old directory alone when there is no UI. Records the current
     * location regardless of the answer -- the question is asked once, and
     * declining means keep, not ask again every launch.
     */
    private void offerPreviousEnvCleanup() {
        final Path current = getEnvironmentPath();
        final String previous = qupath.ext.ppm.PPMPreferences.getEnvLastBuiltDir();
        qupath.ext.ppm.PPMPreferences.setEnvLastBuiltDir(current.toString());
        if (previous == null || previous.isBlank() || previous.equals(current.toString())) {
            return;
        }
        try {
            javafx.application.Platform.runLater(() -> {
                try {
                    ApposeEnvLocation.promptCleanup(Path.of(previous), current);
                } catch (Exception e) {
                    logger.warn("Previous-environment cleanup prompt failed: {}", e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            logger.info("Previous environment at {} left in place (no UI available)", previous);
        }
    }
}
