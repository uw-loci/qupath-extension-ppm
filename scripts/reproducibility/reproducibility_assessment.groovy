// reproducibility_assessment.groovy
//
// QuPath script: reproducibility metrics across N repeat PPM birefringence
// acquisitions of the same MetroHealth target region.
//
// FULL-RESOLUTION, TILE-STREAMED, MULTITHREADED. No whole-image arrays.
// Memory footprint = (THREADS * IN_FLIGHT_PER_THREAD) tiles in flight.
//
// Three phases:
//   PHASE 1 - biref pixel agreement at NATIVE source resolution:
//             per-image stats (sums + histogram for percentiles)
//             pairwise stats (online sums for Pearson r, RMSE, MAE, Bland-Altman)
//   PHASE 2 - collagen mask agreement at the classifier's NATIVE resolution:
//             per-image collagen pixel count + area fraction
//             pairwise TP / FN / FP for Dice / Jaccard
//             across-all intersection / union (any-vs-all-k agreement)
//   PHASE 3 - low-resolution visualization PNGs (CV map, agreement-count map).
//
// Run from QuPath:
//   Automate -> Show script editor -> open this file -> Run.
//
// CONFIG KNOBS (top of file):
//   IMAGE_NAME_PATTERN    - regex for the 4 acquisitions
//   TILE_SIZE             - tile edge in pixels (1024 is a sensible default)
//   THREADS               - worker threads (default = cores - 1, capped at 8)
//   IN_FLIGHT_PER_THREAD  - bound on submitted-but-unprocessed tiles per thread
//   RUN_PHASE_1, RUN_PHASE_2, RUN_PHASE_3 - skip phases independently
//   THRESHOLDER_NAME      - "collagen"
//   POSITIVE_CLASS_NAME   - which class counts as positive in the mask
//   PROGRESS_EVERY_TILES  - logging frequency
//
// THREADING NOTES:
//   - QuPath ImageServers and PixelClassificationImageServers are read-safe
//     for parallel readRegion() calls; this is the same pattern QuPath
//     uses internally for parallel tile processing.
//   - Workers compute LOCAL per-tile partial sums and return them; merge into
//     global accumulators happens on the main thread, no locking needed inside
//     workers.
//   - Bounded in-flight work prevents queue blowup on slow merging.
//   - For Phase 2 the bottleneck is the classifier's per-tile inference; parallelism
//     scales with how many tiles the classifier can process concurrently.
//
// ASSUMPTION: the four acquisitions are spatially registered. An unregistered
// run reports low agreement that reflects mis-registration, not the scope.
//
// Outputs in PROJECT_BASE_DIR/reproducibility_output/:
//   per_image_stats.csv         PHASE 1 - biref distribution per image
//   pairwise_metrics.csv        PHASE 1 - biref pairwise agreement
//   collagen_per_image.csv      PHASE 2 - collagen area / fraction per image
//   collagen_pairwise.csv       PHASE 2 - pairwise Dice / Jaccard / TP / FN / FP
//   collagen_across_all.csv     PHASE 2 - intersection / union of all masks, area-fraction CV
//   cv_map.png                  PHASE 3 - per-pixel CV (low-res)
//   collagen_agreement_map.png  PHASE 3 - per-pixel positive count (low-res)

import qupath.lib.regions.RegionRequest
import qupath.lib.classifiers.pixel.PixelClassificationImageServer
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.PixelType
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ---------- CONFIG ----------
def IMAGE_NAME_PATTERN = ~/MetroHealth_384_20x_54099_52354_7\.0_biref_0(01|04|05|14)\.ome\.tif/
def TILE_SIZE = 1024
def THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1))
def IN_FLIGHT_PER_THREAD = 4
def CHANNEL = 0
def THRESHOLDER_NAME = "collagen"
def POSITIVE_CLASS_NAME = "collagen"
def CV_THRESHOLDS = [0.05, 0.10, 0.20]
def VIZ_LONG_EDGE_PX = 2048
def HIST_BINS = 1024
def PROGRESS_EVERY_TILES = 50
def RUN_PHASE_1 = true
def RUN_PHASE_2 = true
def RUN_PHASE_3 = true
def OUTPUT_DIR = buildFilePath(PROJECT_BASE_DIR, "reproducibility_output")
mkdirs(OUTPUT_DIR)

print "Threads: ${THREADS}, in-flight cap: ${THREADS * IN_FLIGHT_PER_THREAD}"

// ---------- LOCATE IMAGES ----------
def project = getProject()
if (project == null) { print "No project open."; return }

def entries = project.getImageList().findAll { e ->
    e.getImageName() =~ IMAGE_NAME_PATTERN
}.sort { it.getImageName() }

if (entries.size() < 2) {
    print "Found ${entries.size()} matching images; need at least 2."
    print "Pattern: ${IMAGE_NAME_PATTERN}"
    return
}
print "Found ${entries.size()} matching images:"
entries.each { print "  - " + it.getImageName() }

// ---------- OPEN IMAGE DATAS / SERVERS ----------
def imageDatas = entries.collect { it.readImageData() }
def servers = imageDatas.collect { it.getServer() }
def names = entries.collect { it.getImageName() }
int K = servers.size()

int W = servers[0].getWidth()
int H = servers[0].getHeight()
for (int i = 1; i < K; i++) {
    if (servers[i].getWidth() != W || servers[i].getHeight() != H) {
        print "ERROR: dimension mismatch at native resolution."
        print "  ${names[i]}: ${servers[i].getWidth()} x ${servers[i].getHeight()}    ref: ${W} x ${H}"
        servers.each { it.close() }
        return
    }
}
print "Native resolution: ${W} x ${H} (${(long)W * H} pixels per image)"

int N_PAIRS = K * (K - 1) / 2
def pairIdx = { int i, int j -> (i * K - i * (i + 1) / 2 + (j - i - 1)) as int }

// ---------- VISUALIZATION BUFFER SETUP ----------
// Viz maps are accumulated INSIDE the Phase 1 / Phase 2 tile passes (not in a
// separate Phase 3 pass) so we don't pay for redundant classifier inference.
// Phase 3 just writes PNGs from these accumulators.
double VIZ_DS = Math.max(1.0, Math.max(W, H) / (double) VIZ_LONG_EDGE_PX)
int VIZ_W = (int) Math.ceil(W / VIZ_DS)
int VIZ_H = (int) Math.ceil(H / VIZ_DS)
int VIZ_N = VIZ_W * VIZ_H
print "Visualization grid: ${VIZ_W} x ${VIZ_H} (downsample ${VIZ_DS})"

// Phase 1 viz: per-viz-pixel sum / sumSq / count across all K acquisitions
double[] gVizSum   = RUN_PHASE_3 ? new double[VIZ_N] : null
double[] gVizSumSq = RUN_PHASE_3 ? new double[VIZ_N] : null
long[]   gVizCnt   = RUN_PHASE_3 ? new long[VIZ_N]   : null

// Phase 2 viz: per-viz-pixel sum-of-positive-counts and source-pixel count
// (mean positive count per source pixel, scaled to 0..255 in PNG)
long[] gAgreeSum = RUN_PHASE_3 ? new long[VIZ_N] : null
long[] gAgreeCnt = RUN_PHASE_3 ? new long[VIZ_N] : null

// ---------- CLASSIFIER SETUP ----------
def classifier = null
def classifierServers = null
int positiveClassIdx = 1
boolean useExactClass = false
double classifierDS = 1.0
if (RUN_PHASE_2 || RUN_PHASE_3) {
    try {
        classifier = loadPixelClassifier(THRESHOLDER_NAME)
        int found = -1
        classifier.getMetadata().getClassificationLabels().each { Integer idx, pathClass ->
            if (pathClass != null && pathClass.getName() != null
                    && pathClass.getName().equalsIgnoreCase(POSITIVE_CLASS_NAME)) {
                found = idx
            }
        }
        if (found >= 0) { positiveClassIdx = found; useExactClass = true }
        else            { positiveClassIdx = 1; useExactClass = false }
        // Wrap each ImageData with a classifier-output server. Try a few APIs that vary by QuPath version.
        classifierServers = imageDatas.collect { id ->
            try {
                return PixelClassifierTools.createPixelClassificationServer(id, classifier)
            } catch (Throwable t1) {
                try {
                    return new PixelClassificationImageServer(id, classifier)
                } catch (Throwable t2) {
                    throw new RuntimeException("Could not construct classifier server: ${t1.message} / ${t2.message}", t2)
                }
            }
        }
        classifierDS = classifierServers[0].getPreferredDownsamples()[0]
        print "Loaded thresholder '${THRESHOLDER_NAME}': positive class index=${positiveClassIdx} (exact=${useExactClass}), classifier downsample=${classifierDS}"
    } catch (Throwable t) {
        print "WARNING: could not load thresholder '${THRESHOLDER_NAME}': ${t.message}"
        print "Phase 2 / Phase 3 collagen outputs will be skipped."
        classifier = null
        classifierServers = null
    }
}

// =====================================================================
// PHASE 1 - BIREF PIXEL AGREEMENT AT NATIVE RESOLUTION (multithreaded)
// =====================================================================

def perImage = null
def pairs = null

if (RUN_PHASE_1) {
    def pixelType = servers[0].getPixelType()
    double minVal = 0.0, maxVal
    if (pixelType == PixelType.UINT8)        maxVal = 255.0
    else if (pixelType == PixelType.UINT16)  maxVal = 65535.0
    else if (pixelType == PixelType.INT16)   maxVal = 65535.0
    else { maxVal = 65535.0; print "WARNING: pixel type ${pixelType}; histogram range guessed as [0, 65535]" }
    final double MINVAL = minVal
    final double BIN_WIDTH = (maxVal - minVal) / HIST_BINS
    final int FBINS = HIST_BINS
    final int FK = K
    final int FNPAIRS = N_PAIRS
    final int FCHANNEL = CHANNEL

    long[]   N1     = new long[K]
    double[] SUM    = new double[K]
    double[] SUMSQ  = new double[K]
    double[] MN     = new double[K]
    double[] MX     = new double[K]
    for (int i = 0; i < K; i++) { MN[i] = Double.POSITIVE_INFINITY; MX[i] = Double.NEGATIVE_INFINITY }
    long[][] HIST = new long[K][HIST_BINS]

    double[] PA_SA       = new double[N_PAIRS]
    double[] PA_SB       = new double[N_PAIRS]
    double[] PA_SAA      = new double[N_PAIRS]
    double[] PA_SBB      = new double[N_PAIRS]
    double[] PA_SAB      = new double[N_PAIRS]
    double[] PA_SDIFF    = new double[N_PAIRS]
    double[] PA_SDIFFSQ  = new double[N_PAIRS]
    double[] PA_SABSDIFF = new double[N_PAIRS]
    long[]   PA_N        = new long[N_PAIRS]

    int nTilesX = (int) Math.ceil((double) W / TILE_SIZE)
    int nTilesY = (int) Math.ceil((double) H / TILE_SIZE)
    long totalTiles = (long) nTilesX * nTilesY
    print "PHASE 1: streaming ${totalTiles} tiles (${nTilesX} x ${nTilesY}) at ${TILE_SIZE} px native, ${THREADS} threads..."

    def srvs = servers as ImageServer[]
    def pool = Executors.newFixedThreadPool(THREADS)
    def cs   = new ExecutorCompletionService(pool)
    int maxInFlight = THREADS * IN_FLIGHT_PER_THREAD
    final boolean DO_VIZ = (gVizSum != null)
    final double FVDS = VIZ_DS
    final int FVW = VIZ_W
    final int FVH = VIZ_H

    // Worker: returns a Map of partial accumulators for ONE tile.
    def makeWorker = { final int tileX, final int tileY, final int tileW, final int tileH ->
        return ({
            int n = tileW * tileH
            float[][] tilePixels = new float[FK][n]
            for (int i = 0; i < FK; i++) {
                def req = RegionRequest.createInstance(srvs[i].getPath(), 1.0, tileX, tileY, tileW, tileH)
                def img = srvs[i].readRegion(req)
                int bands = img.getRaster().getNumBands()
                int chSafe = Math.min(FCHANNEL, bands - 1)
                int[] tmp = new int[n]
                img.getRaster().getSamples(0, 0, tileW, tileH, chSafe, tmp)
                float[] fa = tilePixels[i]
                for (int p = 0; p < n; p++) fa[p] = (float) tmp[p]
            }
            double[] lSUM = new double[FK]
            double[] lSUMSQ = new double[FK]
            double[] lMN = new double[FK]
            double[] lMX = new double[FK]
            for (int i = 0; i < FK; i++) { lMN[i] = Double.POSITIVE_INFINITY; lMX[i] = Double.NEGATIVE_INFINITY }
            long[][] lHIST = new long[FK][FBINS]
            for (int i = 0; i < FK; i++) {
                float[] a = tilePixels[i]
                long[] hi = lHIST[i]
                double Si = 0, SSi = 0
                double mni = Double.POSITIVE_INFINITY, mxi = Double.NEGATIVE_INFINITY
                for (int p = 0; p < n; p++) {
                    double v = a[p]
                    Si  += v
                    SSi += v * v
                    if (v < mni) mni = v
                    if (v > mxi) mxi = v
                    int b = (int) ((v - MINVAL) / BIN_WIDTH)
                    if (b < 0) b = 0
                    else if (b >= FBINS) b = FBINS - 1
                    hi[b]++
                }
                lSUM[i] = Si
                lSUMSQ[i] = SSi
                lMN[i] = mni
                lMX[i] = mxi
            }
            double[] lPA_SA       = new double[FNPAIRS]
            double[] lPA_SB       = new double[FNPAIRS]
            double[] lPA_SAA      = new double[FNPAIRS]
            double[] lPA_SBB      = new double[FNPAIRS]
            double[] lPA_SAB      = new double[FNPAIRS]
            double[] lPA_SDIFF    = new double[FNPAIRS]
            double[] lPA_SDIFFSQ  = new double[FNPAIRS]
            double[] lPA_SABSDIFF = new double[FNPAIRS]
            for (int i = 0; i < FK; i++) {
                float[] a = tilePixels[i]
                for (int j = i + 1; j < FK; j++) {
                    int pi = (i * FK - i * (i + 1) / 2 + (j - i - 1)) as int
                    float[] b = tilePixels[j]
                    double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0
                    double sdiff = 0, sdiffsq = 0, sabsdiff = 0
                    for (int p = 0; p < n; p++) {
                        double va = a[p], vb = b[p]
                        sa += va;  sb += vb
                        saa += va * va;  sbb += vb * vb;  sab += va * vb
                        double d = va - vb
                        sdiff += d
                        sdiffsq += d * d
                        sabsdiff += Math.abs(d)
                    }
                    lPA_SA[pi]       = sa
                    lPA_SB[pi]       = sb
                    lPA_SAA[pi]      = saa
                    lPA_SBB[pi]      = sbb
                    lPA_SAB[pi]      = sab
                    lPA_SDIFF[pi]    = sdiff
                    lPA_SDIFFSQ[pi]  = sdiffsq
                    lPA_SABSDIFF[pi] = sabsdiff
                }
            }
            // Viz accumulation (Phase 3 source CV map). Add this tile's contribution
            // into a small viz-tile-local buffer that the main thread merges into globals.
            int lvw = 0, lvh = 0, vizX0 = 0, vizY0 = 0
            double[] lVizSum = null, lVizSumSq = null
            long[]   lVizCnt = null
            if (DO_VIZ) {
                vizX0 = (int)(tileX / FVDS)
                vizY0 = (int)(tileY / FVDS)
                int vizX1 = Math.min((int)((tileX + tileW - 1) / FVDS) + 1, FVW)
                int vizY1 = Math.min((int)((tileY + tileH - 1) / FVDS) + 1, FVH)
                lvw = Math.max(0, vizX1 - vizX0)
                lvh = Math.max(0, vizY1 - vizY0)
                if (lvw > 0 && lvh > 0) {
                    lVizSum   = new double[lvw * lvh]
                    lVizSumSq = new double[lvw * lvh]
                    lVizCnt   = new long[lvw * lvh]
                    int[] vizCol = new int[tileW]
                    for (int xx = 0; xx < tileW; xx++) {
                        int v = (int)((tileX + xx) / FVDS) - vizX0
                        if (v < 0) v = 0
                        else if (v >= lvw) v = lvw - 1
                        vizCol[xx] = v
                    }
                    for (int yy = 0; yy < tileH; yy++) {
                        int vyLocal = (int)((tileY + yy) / FVDS) - vizY0
                        if (vyLocal < 0) vyLocal = 0
                        else if (vyLocal >= lvh) vyLocal = lvh - 1
                        int vyOff = vyLocal * lvw
                        int sOff = yy * tileW
                        for (int xx = 0; xx < tileW; xx++) {
                            int vidx = vyOff + vizCol[xx]
                            int sidx = sOff + xx
                            double s = 0, ss = 0
                            for (int i = 0; i < FK; i++) {
                                double v = tilePixels[i][sidx]
                                s += v
                                ss += v * v
                            }
                            lVizSum[vidx]   += s
                            lVizSumSq[vidx] += ss
                            lVizCnt[vidx]   += FK
                        }
                    }
                }
            }
            return [n: (long) n, lSUM: lSUM, lSUMSQ: lSUMSQ, lMN: lMN, lMX: lMX, lHIST: lHIST,
                    lPA_SA: lPA_SA, lPA_SB: lPA_SB, lPA_SAA: lPA_SAA, lPA_SBB: lPA_SBB, lPA_SAB: lPA_SAB,
                    lPA_SDIFF: lPA_SDIFF, lPA_SDIFFSQ: lPA_SDIFFSQ, lPA_SABSDIFF: lPA_SABSDIFF,
                    lVizSum: lVizSum, lVizSumSq: lVizSumSq, lVizCnt: lVizCnt,
                    vizX0: vizX0, vizY0: vizY0, lvw: lvw, lvh: lvh]
        } as Callable)
    }

    def mergeP1 = { Map r ->
        long n = r.n as long
        for (int i = 0; i < FK; i++) {
            SUM[i]   += (r.lSUM as double[])[i]
            SUMSQ[i] += (r.lSUMSQ as double[])[i]
            double rmn = (r.lMN as double[])[i]; if (rmn < MN[i]) MN[i] = rmn
            double rmx = (r.lMX as double[])[i]; if (rmx > MX[i]) MX[i] = rmx
            long[] src = (r.lHIST as long[][])[i]
            long[] dst = HIST[i]
            for (int b = 0; b < FBINS; b++) dst[b] += src[b]
            N1[i] += n
        }
        double[] rSA = r.lPA_SA, rSB = r.lPA_SB, rSAA = r.lPA_SAA, rSBB = r.lPA_SBB, rSAB = r.lPA_SAB
        double[] rSD = r.lPA_SDIFF, rSDS = r.lPA_SDIFFSQ, rSAD = r.lPA_SABSDIFF
        for (int p = 0; p < FNPAIRS; p++) {
            PA_SA[p]       += rSA[p]
            PA_SB[p]       += rSB[p]
            PA_SAA[p]      += rSAA[p]
            PA_SBB[p]      += rSBB[p]
            PA_SAB[p]      += rSAB[p]
            PA_SDIFF[p]    += rSD[p]
            PA_SDIFFSQ[p]  += rSDS[p]
            PA_SABSDIFF[p] += rSAD[p]
            PA_N[p]        += n
        }
        if (DO_VIZ && r.lVizSum != null) {
            int vx0 = r.vizX0 as int, vy0 = r.vizY0 as int
            int rvw = r.lvw as int, rvh = r.lvh as int
            double[] rVS = r.lVizSum, rVSS = r.lVizSumSq
            long[] rVC = r.lVizCnt
            for (int yy = 0; yy < rvh; yy++) {
                int gy = vy0 + yy
                if (gy < 0 || gy >= FVH) continue
                int gOff = gy * FVW
                int rOff = yy * rvw
                for (int xx = 0; xx < rvw; xx++) {
                    int gx = vx0 + xx
                    if (gx < 0 || gx >= FVW) continue
                    gVizSum[gOff + gx]   += rVS[rOff + xx]
                    gVizSumSq[gOff + gx] += rVSS[rOff + xx]
                    gVizCnt[gOff + gx]   += rVC[rOff + xx]
                }
            }
        }
    }

    long startMs = System.currentTimeMillis()
    long submitted = 0, completed = 0
    for (int ty = 0; ty < nTilesY; ty++) {
        int yL = ty * TILE_SIZE
        int hL = Math.min(TILE_SIZE, H - yL)
        for (int tx = 0; tx < nTilesX; tx++) {
            int xL = tx * TILE_SIZE
            int wL = Math.min(TILE_SIZE, W - xL)
            while (submitted - completed >= maxInFlight) {
                def f = cs.take()
                mergeP1(f.get() as Map)
                completed++
                if (completed % PROGRESS_EVERY_TILES == 0) {
                    long el = System.currentTimeMillis() - startMs
                    double rate = (double) completed / Math.max(0.001, el / 1000.0)
                    long etaSec = (long) ((totalTiles - completed) / rate)
                    print sprintf("  PHASE 1: %d / %d  (%.1f tiles/s, ETA %d s)", completed, totalTiles, rate, etaSec)
                }
            }
            cs.submit(makeWorker(xL, yL, wL, hL))
            submitted++
        }
    }
    while (completed < submitted) {
        def f = cs.take()
        mergeP1(f.get() as Map)
        completed++
        if (completed % PROGRESS_EVERY_TILES == 0 || completed == submitted) {
            long el = System.currentTimeMillis() - startMs
            double rate = (double) completed / Math.max(0.001, el / 1000.0)
            long etaSec = (long) ((totalTiles - completed) / rate)
            print sprintf("  PHASE 1: %d / %d  (%.1f tiles/s, ETA %d s)", completed, totalTiles, rate, etaSec)
        }
    }
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.SECONDS)
    print sprintf("PHASE 1: complete in %.1f s", (System.currentTimeMillis() - startMs) / 1000.0)

    perImage = []
    for (int i = 0; i < K; i++) {
        double mean = SUM[i] / N1[i]
        double var = Math.max(0.0, SUMSQ[i] / N1[i] - mean * mean)
        double sd = Math.sqrt(var)
        long t1 = (long) (0.01 * N1[i])
        long t5 = (long) (0.05 * N1[i])
        long t50 = (long) (0.50 * N1[i])
        long t95 = (long) (0.95 * N1[i])
        long t99 = (long) (0.99 * N1[i])
        long cum = 0
        double p01 = MINVAL, p05 = MINVAL, p50 = MINVAL, p95 = MINVAL, p99 = MINVAL
        boolean d1 = false, d5 = false, d50 = false, d95 = false, d99 = false
        for (int b = 0; b < HIST_BINS; b++) {
            cum += HIST[i][b]
            double v = MINVAL + (b + 0.5) * BIN_WIDTH
            if (!d1  && cum >= t1)  { p01 = v; d1  = true }
            if (!d5  && cum >= t5)  { p05 = v; d5  = true }
            if (!d50 && cum >= t50) { p50 = v; d50 = true }
            if (!d95 && cum >= t95) { p95 = v; d95 = true }
            if (!d99 && cum >= t99) { p99 = v; d99 = true }
        }
        perImage << [n: N1[i], mean: mean, sd: sd, min: MN[i], max: MX[i],
                     p01: p01, p05: p05, p50: p50, p95: p95, p99: p99]
    }

    pairs = []
    for (int i = 0; i < K; i++) {
        for (int j = i + 1; j < K; j++) {
            int pi = pairIdx(i, j)
            long Np = PA_N[pi]
            double ma = PA_SA[pi] / Np
            double mb = PA_SB[pi] / Np
            double va = Math.max(0.0, PA_SAA[pi] / Np - ma * ma)
            double vb = Math.max(0.0, PA_SBB[pi] / Np - mb * mb)
            double covAB = PA_SAB[pi] / Np - ma * mb
            double pearson = (va > 0 && vb > 0) ? covAB / Math.sqrt(va * vb) : Double.NaN
            double rmse = Math.sqrt(PA_SDIFFSQ[pi] / Np)
            double mae  = PA_SABSDIFF[pi] / Np
            double bias = PA_SDIFF[pi] / Np
            double sdDiff = Math.sqrt(Math.max(0.0, PA_SDIFFSQ[pi] / Np - bias * bias))
            pairs << [a: names[i], b: names[j], pearson: pearson, rmse: rmse, mae: mae,
                      bias: bias, sdDiff: sdDiff,
                      loaLow: bias - 1.96 * sdDiff, loaHigh: bias + 1.96 * sdDiff]
        }
    }
} else {
    print "PHASE 1 skipped (RUN_PHASE_1 = false)."
}

// =====================================================================
// PHASE 2 - COLLAGEN MASK AGREEMENT (multithreaded)
// =====================================================================

def maskAreas = null
def maskFractions = null
def maskPairs = []
def collagenAcrossAll = null
long maskTotalPixels = 0L

if (RUN_PHASE_2 && classifierServers != null) {
    final int FK = K
    final int FNPAIRS = N_PAIRS
    final int FPOS = positiveClassIdx
    final boolean FEXACT = useExactClass
    def cservs = classifierServers as ImageServer[]
    final double FDS = classifierDS

    long[] CA       = new long[K]
    long[] TP       = new long[N_PAIRS]
    long[] FN_AB    = new long[N_PAIRS]
    long[] FP_AB    = new long[N_PAIRS]
    long[] INTER_ALL = new long[1]
    long[] UNION_ALL = new long[1]
    long[] TOT_PX    = new long[1]

    int strideSrc = (int) Math.round(TILE_SIZE * classifierDS)
    int nTilesX = (int) Math.ceil((double) W / strideSrc)
    int nTilesY = (int) Math.ceil((double) H / strideSrc)
    long totalTiles = (long) nTilesX * nTilesY
    print "PHASE 2: streaming ${totalTiles} tiles at classifier downsample ${classifierDS}, ${THREADS} threads..."
    print "  Classifier server logical dims: ${classifierServers[0].getWidth()} x ${classifierServers[0].getHeight()}"
    print "  NOTE: per-tile runtime is dominated by classifier inference; speedup scales with classifier thread-safety."

    def pool = Executors.newFixedThreadPool(THREADS)
    def cs   = new ExecutorCompletionService(pool)
    int maxInFlight = THREADS * IN_FLIGHT_PER_THREAD
    final boolean DO_VIZ2 = (gAgreeSum != null)
    final double FVDS2 = VIZ_DS
    final int FVW2 = VIZ_W
    final int FVH2 = VIZ_H

    def makeMaskWorker = { final int srcX, final int srcY, final int srcW, final int srcH ->
        return ({
            BufferedImage[] imgs = new BufferedImage[FK]
            for (int i = 0; i < FK; i++) {
                def req = RegionRequest.createInstance(cservs[i].getPath(), FDS, srcX, srcY, srcW, srcH)
                imgs[i] = cservs[i].readRegion(req)
            }
            int tw = imgs[0].getWidth()
            int th = imgs[0].getHeight()
            int n = tw * th
            boolean[][] maskTiles = new boolean[FK][n]
            long[] lCA = new long[FK]
            for (int i = 0; i < FK; i++) {
                int[] tmp = new int[n]
                imgs[i].getRaster().getSamples(0, 0, tw, th, 0, tmp)
                boolean[] m = maskTiles[i]
                long c = 0
                if (FEXACT) {
                    for (int p = 0; p < n; p++) { boolean v = (tmp[p] == FPOS); m[p] = v; if (v) c++ }
                } else {
                    for (int p = 0; p < n; p++) { boolean v = (tmp[p] != 0); m[p] = v; if (v) c++ }
                }
                lCA[i] = c
            }
            long[] lTP = new long[FNPAIRS]
            long[] lFN = new long[FNPAIRS]
            long[] lFP = new long[FNPAIRS]
            for (int i = 0; i < FK; i++) {
                boolean[] a = maskTiles[i]
                for (int j = i + 1; j < FK; j++) {
                    int pi = (i * FK - i * (i + 1) / 2 + (j - i - 1)) as int
                    boolean[] b = maskTiles[j]
                    long tp = 0, fn = 0, fp = 0
                    for (int p = 0; p < n; p++) {
                        boolean av = a[p], bv = b[p]
                        if (av && bv) tp++
                        else if (av)  fn++
                        else if (bv)  fp++
                    }
                    lTP[pi] = tp; lFN[pi] = fn; lFP[pi] = fp
                }
            }
            long lInter = 0, lUnion = 0
            int[] perPixCount = new int[n]
            for (int p = 0; p < n; p++) {
                int c = 0
                for (int i = 0; i < FK; i++) if (maskTiles[i][p]) c++
                perPixCount[p] = c
                if (c == FK) lInter++
                if (c >  0)  lUnion++
            }

            // Viz accumulation (Phase 3 collagen agreement map). Per-tile partial
            // viz buffer keyed in viz pixel coords; merged into globals on main thread.
            int lvw = 0, lvh = 0, vizX0 = 0, vizY0 = 0
            long[] lAgreeSum = null, lAgreeCnt = null
            if (DO_VIZ2) {
                vizX0 = (int)(srcX / FVDS2)
                vizY0 = (int)(srcY / FVDS2)
                int vizX1 = Math.min((int)((srcX + srcW - 1) / FVDS2) + 1, FVW2)
                int vizY1 = Math.min((int)((srcY + srcH - 1) / FVDS2) + 1, FVH2)
                lvw = Math.max(0, vizX1 - vizX0)
                lvh = Math.max(0, vizY1 - vizY0)
                if (lvw > 0 && lvh > 0) {
                    lAgreeSum = new long[lvw * lvh]
                    lAgreeCnt = new long[lvw * lvh]
                    // Each classifier-resolution pixel covers (FDS x FDS) source pixels.
                    // Map classifier pixel (cx, cy) -> source coord (srcX + cx*FDS, srcY + cy*FDS).
                    // Then map to viz coord.
                    for (int cy = 0; cy < th; cy++) {
                        double srcYy = srcY + cy * FDS
                        int vyLocal = (int)(srcYy / FVDS2) - vizY0
                        if (vyLocal < 0) vyLocal = 0
                        else if (vyLocal >= lvh) vyLocal = lvh - 1
                        int vyOff = vyLocal * lvw
                        int sOff = cy * tw
                        for (int cx = 0; cx < tw; cx++) {
                            double srcXx = srcX + cx * FDS
                            int vxLocal = (int)(srcXx / FVDS2) - vizX0
                            if (vxLocal < 0) vxLocal = 0
                            else if (vxLocal >= lvw) vxLocal = lvw - 1
                            int vidx = vyOff + vxLocal
                            lAgreeSum[vidx] += perPixCount[sOff + cx]
                            lAgreeCnt[vidx] += 1
                        }
                    }
                }
            }
            return [n: (long) n, lCA: lCA, lTP: lTP, lFN: lFN, lFP: lFP,
                    lInter: lInter, lUnion: lUnion,
                    lAgreeSum: lAgreeSum, lAgreeCnt: lAgreeCnt,
                    vizX0: vizX0, vizY0: vizY0, lvw: lvw, lvh: lvh]
        } as Callable)
    }

    def mergeP2 = { Map r ->
        long n = r.n as long
        long[] rCA = r.lCA
        for (int i = 0; i < FK; i++) CA[i] += rCA[i]
        long[] rTP = r.lTP, rFN = r.lFN, rFP = r.lFP
        for (int p = 0; p < FNPAIRS; p++) {
            TP[p]    += rTP[p]
            FN_AB[p] += rFN[p]
            FP_AB[p] += rFP[p]
        }
        INTER_ALL[0] += (r.lInter as long)
        UNION_ALL[0] += (r.lUnion as long)
        TOT_PX[0]    += n
        if (DO_VIZ2 && r.lAgreeSum != null) {
            int vx0 = r.vizX0 as int, vy0 = r.vizY0 as int
            int rvw = r.lvw as int, rvh = r.lvh as int
            long[] rAS = r.lAgreeSum, rAC = r.lAgreeCnt
            for (int yy = 0; yy < rvh; yy++) {
                int gy = vy0 + yy
                if (gy < 0 || gy >= FVH2) continue
                int gOff = gy * FVW2
                int rOff = yy * rvw
                for (int xx = 0; xx < rvw; xx++) {
                    int gx = vx0 + xx
                    if (gx < 0 || gx >= FVW2) continue
                    gAgreeSum[gOff + gx] += rAS[rOff + xx]
                    gAgreeCnt[gOff + gx] += rAC[rOff + xx]
                }
            }
        }
    }

    long startMs = System.currentTimeMillis()
    long submitted = 0, completed = 0
    for (int ty = 0; ty < nTilesY; ty++) {
        int yL = ty * strideSrc
        int hL = Math.min(strideSrc, H - yL)
        for (int tx = 0; tx < nTilesX; tx++) {
            int xL = tx * strideSrc
            int wL = Math.min(strideSrc, W - xL)
            while (submitted - completed >= maxInFlight) {
                def f = cs.take()
                mergeP2(f.get() as Map)
                completed++
                if (completed % PROGRESS_EVERY_TILES == 0) {
                    long el = System.currentTimeMillis() - startMs
                    double rate = (double) completed / Math.max(0.001, el / 1000.0)
                    long etaSec = (long) ((totalTiles - completed) / rate)
                    print sprintf("  PHASE 2: %d / %d  (%.2f tiles/s, ETA %d s)", completed, totalTiles, rate, etaSec)
                }
            }
            cs.submit(makeMaskWorker(xL, yL, wL, hL))
            submitted++
        }
    }
    while (completed < submitted) {
        def f = cs.take()
        mergeP2(f.get() as Map)
        completed++
        if (completed % PROGRESS_EVERY_TILES == 0 || completed == submitted) {
            long el = System.currentTimeMillis() - startMs
            double rate = (double) completed / Math.max(0.001, el / 1000.0)
            long etaSec = (long) ((totalTiles - completed) / rate)
            print sprintf("  PHASE 2: %d / %d  (%.2f tiles/s, ETA %d s)", completed, totalTiles, rate, etaSec)
        }
    }
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.SECONDS)
    print sprintf("PHASE 2: complete in %.1f s", (System.currentTimeMillis() - startMs) / 1000.0)

    maskTotalPixels = TOT_PX[0]
    maskAreas = CA
    maskFractions = new double[K]
    for (int i = 0; i < K; i++) maskFractions[i] = (double) CA[i] / maskTotalPixels

    maskPairs = []
    for (int i = 0; i < K; i++) {
        for (int j = i + 1; j < K; j++) {
            int pi = pairIdx(i, j)
            long aArea = CA[i], bArea = CA[j]
            long inter = TP[pi]
            long union = aArea + bArea - inter
            double dice = (aArea + bArea > 0) ? (2.0 * inter) / (double)(aArea + bArea) : Double.NaN
            double jaccard = (union > 0) ? (double) inter / union : Double.NaN
            maskPairs << [a: names[i], b: names[j],
                          area_a: aArea, area_b: bArea,
                          intersection: inter, union: union,
                          a_minus_b: FN_AB[pi], b_minus_a: FP_AB[pi],
                          dice: dice, jaccard: jaccard]
        }
    }

    double areaMean = 0
    for (int i = 0; i < K; i++) areaMean += maskFractions[i]
    areaMean /= K
    double areaVar = 0
    for (int i = 0; i < K; i++) { double d = maskFractions[i] - areaMean; areaVar += d * d }
    areaVar /= K
    double areaSd = Math.sqrt(areaVar)
    double areaCv = (areaMean > 1e-12) ? areaSd / areaMean : Double.NaN

    collagenAcrossAll = [
        intersection_all : INTER_ALL[0],
        union_all        : UNION_ALL[0],
        agreement_ratio  : UNION_ALL[0] > 0 ? (double) INTER_ALL[0] / UNION_ALL[0] : Double.NaN,
        area_frac_mean   : areaMean,
        area_frac_sd     : areaSd,
        area_frac_cv     : areaCv
    ]
} else if (RUN_PHASE_2) {
    print "PHASE 2 skipped (no classifier)."
} else {
    print "PHASE 2 skipped (RUN_PHASE_2 = false)."
}

// =====================================================================
// WRITE CSVs
// =====================================================================

if (perImage != null) {
    new File(OUTPUT_DIR, "per_image_stats.csv").withPrintWriter { pw ->
        pw.println "image,n_pixels,mean,sd,min,max,p01,p05,p50,p95,p99"
        perImage.eachWithIndex { s, i ->
            pw.println "${names[i]},${s.n},${s.mean},${s.sd},${s.min},${s.max},${s.p01},${s.p05},${s.p50},${s.p95},${s.p99}"
        }
    }
}
if (pairs != null) {
    new File(OUTPUT_DIR, "pairwise_metrics.csv").withPrintWriter { pw ->
        pw.println "image_a,image_b,pearson_r,rmse,mae,bias,sd_diff,loa_low,loa_high"
        pairs.each { p ->
            pw.println "${p.a},${p.b},${p.pearson},${p.rmse},${p.mae},${p.bias},${p.sdDiff},${p.loaLow},${p.loaHigh}"
        }
    }
}
if (maskAreas != null) {
    new File(OUTPUT_DIR, "collagen_per_image.csv").withPrintWriter { pw ->
        pw.println "image,collagen_pixels,total_classifier_pixels,area_fraction"
        for (int i = 0; i < K; i++) {
            pw.println "${names[i]},${maskAreas[i]},${maskTotalPixels},${maskFractions[i]}"
        }
    }
    new File(OUTPUT_DIR, "collagen_pairwise.csv").withPrintWriter { pw ->
        pw.println "image_a,image_b,area_a,area_b,intersection,union,a_minus_b,b_minus_a,dice,jaccard"
        maskPairs.each { p ->
            pw.println "${p.a},${p.b},${p.area_a},${p.area_b},${p.intersection},${p.union},${p.a_minus_b},${p.b_minus_a},${p.dice},${p.jaccard}"
        }
    }
    new File(OUTPUT_DIR, "collagen_across_all.csv").withPrintWriter { pw ->
        pw.println "metric,value"
        pw.println "n_acquisitions,${K}"
        pw.println "n_classifier_pixels,${maskTotalPixels}"
        pw.println "intersection_all,${collagenAcrossAll.intersection_all}"
        pw.println "union_all,${collagenAcrossAll.union_all}"
        pw.println "agreement_ratio_intersection_over_union,${collagenAcrossAll.agreement_ratio}"
        pw.println "area_fraction_mean,${collagenAcrossAll.area_frac_mean}"
        pw.println "area_fraction_sd,${collagenAcrossAll.area_frac_sd}"
        pw.println "area_fraction_cv,${collagenAcrossAll.area_frac_cv}"
    }
}

// =====================================================================
// PHASE 3 - WRITE VISUALIZATION PNGs FROM ACCUMULATORS BUILT IN PHASES 1/2
// =====================================================================
// No additional reads or classifier work happens here. Buffers are filled
// during the Phase 1 / Phase 2 tile passes; this just renders to PNG.

if (RUN_PHASE_3) {
    print "PHASE 3: writing visualization PNGs from in-stream accumulators (no extra reads)..."

    if (gVizSum != null) {
        double cvMaxForViz = CV_THRESHOLDS.max()
        BufferedImage cvImg = new BufferedImage(VIZ_W, VIZ_H, BufferedImage.TYPE_BYTE_GRAY)
        byte[] gray = new byte[VIZ_N]
        long underThresh = 0, valid = 0
        for (int p = 0; p < VIZ_N; p++) {
            long c = gVizCnt[p]
            if (c < 2) { gray[p] = 0; continue }
            double mean = gVizSum[p] / c
            double var  = Math.max(0.0, gVizSumSq[p] / c - mean * mean)
            double sdp  = Math.sqrt(var)
            double cv   = (mean > 1e-6) ? (sdp / mean) : Double.NaN
            if (!Double.isFinite(cv)) { gray[p] = 0; continue }
            valid++
            if (cv < cvMaxForViz) underThresh++
            double scaled = Math.min(1.0, cv / cvMaxForViz) * 255.0
            gray[p] = (byte) Math.round(scaled)
        }
        cvImg.getRaster().setDataElements(0, 0, VIZ_W, VIZ_H, gray)
        ImageIO.write(cvImg, "png", new File(OUTPUT_DIR, "cv_map.png"))
        print sprintf("  cv_map.png written. valid viz pixels=%d, fraction with CV<%.2f=%.3f",
                valid, cvMaxForViz, valid > 0 ? (double) underThresh / valid : Double.NaN)
    } else {
        print "  cv_map.png skipped (Phase 1 viz buffer not populated)."
    }

    if (gAgreeSum != null) {
        BufferedImage agImg = new BufferedImage(VIZ_W, VIZ_H, BufferedImage.TYPE_BYTE_GRAY)
        byte[] ag = new byte[VIZ_N]
        for (int p = 0; p < VIZ_N; p++) {
            long c = gAgreeCnt[p]
            if (c <= 0) { ag[p] = 0; continue }
            double meanCount = (double) gAgreeSum[p] / c   // 0..K
            ag[p] = (byte) Math.round(Math.min(1.0, meanCount / (double) K) * 255.0)
        }
        agImg.getRaster().setDataElements(0, 0, VIZ_W, VIZ_H, ag)
        ImageIO.write(agImg, "png", new File(OUTPUT_DIR, "collagen_agreement_map.png"))
        print "  collagen_agreement_map.png written."
    } else {
        print "  collagen_agreement_map.png skipped (Phase 2 viz buffer not populated)."
    }
}

// =====================================================================
// LOG SUMMARY
// =====================================================================

if (perImage != null) {
    print "=== PHASE 1: biref pixel agreement (native resolution) ==="
    perImage.eachWithIndex { s, i ->
        print sprintf("%s  N=%d  mean=%.3f  sd=%.3f  p50=%.1f  p95=%.1f", names[i], s.n, s.mean, s.sd, s.p50, s.p95)
    }
    print "--- Pairwise (Pearson r / RMSE / MAE / bias / 95% LoA) ---"
    pairs.each { p ->
        print sprintf("%s vs %s   r=%.4f  rmse=%.3f  mae=%.3f  bias=%.3f  LoA=[%.2f, %.2f]",
                p.a, p.b, p.pearson, p.rmse, p.mae, p.bias, p.loaLow, p.loaHigh)
    }
}
if (maskAreas != null) {
    print "=== PHASE 2: collagen mask agreement (classifier native resolution) ==="
    for (int i = 0; i < K; i++) {
        print sprintf("%s  collagen=%d / %d  fraction=%.5f", names[i], maskAreas[i], maskTotalPixels, maskFractions[i])
    }
    print "--- Pairwise (Dice / Jaccard / TP / a\\b / b\\a) ---"
    maskPairs.each { p ->
        print sprintf("%s vs %s   Dice=%.4f  Jaccard=%.4f  TP=%d  a\\b=%d  b\\a=%d",
                p.a, p.b, p.dice, p.jaccard, p.intersection, p.a_minus_b, p.b_minus_a)
    }
    print "--- Across all ${K} acquisitions ---"
    print sprintf("intersection_all=%d  union_all=%d  intersection/union=%.4f",
            collagenAcrossAll.intersection_all, collagenAcrossAll.union_all, collagenAcrossAll.agreement_ratio)
    print sprintf("collagen area fraction: mean=%.5f  sd=%.5f  CV=%.4f  <-- HEADLINE REPRODUCIBILITY NUMBER",
            collagenAcrossAll.area_frac_mean, collagenAcrossAll.area_frac_sd, collagenAcrossAll.area_frac_cv)
}
print "Outputs written to: ${OUTPUT_DIR}"

servers.each { try { it.close() } catch (Throwable t) {} }
if (classifierServers != null) classifierServers.each { try { it.close() } catch (Throwable t) {} }
