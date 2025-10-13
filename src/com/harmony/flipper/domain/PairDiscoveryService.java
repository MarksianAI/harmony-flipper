package com.harmony.flipper.domain;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.harmony.flipper.config.Config;
import com.harmony.flipper.data.ItemInfo;
import com.harmony.flipper.data.PriceData;
import com.harmony.flipper.util.DoubleRingBuffer;
import com.harmony.flipper.util.NumberUtils;
import org.rspeer.commons.logging.Log;
import org.rspeer.event.Service;
import org.rspeer.event.Subscribe;
import org.rspeer.game.event.TickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers high-correlation pairs using per-item deviation vs 24h averages.
 * - Maintains a rolling buffer of deviation(%) per item.
 * - Periodically scans the top-N volume items and computes pairwise correlations.
 * - Emits pair candidates with corr >= config.strategies.pairsTrading.minCorrelation.
 */
@Singleton
public final class PairDiscoveryService implements Service {

    private final FlipService flipService;
    private final Config config;

    /** Rolling deviation buffers per item (percent deviation vs 24h avg low). */
    private final Map<Integer, DoubleRingBuffer> devBuffers = new ConcurrentHashMap<>();

    /** Optional rolling spread buffers for pairs that pass correlation (for Z-score). */
    private final Map<PairTradingService.PairKey, DoubleRingBuffer> spreadBuffers = new ConcurrentHashMap<>();

    /** Last discovered candidates snapshot. */
    private volatile List<PairCandidate> lastCandidates = List.of();

    /** Configuration-ish knobs (kept internal so we don't add new Config fields). */
    private static final int TOP_N_BY_VOLUME = 300;      // scan top-N liquid items
    private static final int SCAN_EVERY_TICKS = 30;      // compute correlations every N ticks
    private static final int MIN_SAMPLES_FOR_CORR = 30;  // need at least this many aligned samples
    private static final int MAX_OUTPUT_CANDIDATES = 100;

    private int tickSinceScan = 0;

    public static final class PairCandidate {
        public final PairTradingService.PairKey key;
        public final String nameA;
        public final String nameB;
        public final double correlation;
        public final int samples;
        public final double devA;          // latest deviation (%) A
        public final double devB;          // latest deviation (%) B
        public final double spread;        // devA - devB (latest)
        public final Double spreadZ;       // optional (null if insufficient spread history)
        public final int combinedVolume24h;

        public PairCandidate(PairTradingService.PairKey key, String nameA, String nameB,
                             double correlation, int samples,
                             double devA, double devB, double spread, Double spreadZ,
                             int combinedVolume24h) {
            this.key = key;
            this.nameA = nameA;
            this.nameB = nameB;
            this.correlation = correlation;
            this.samples = samples;
            this.devA = devA;
            this.devB = devB;
            this.spread = spread;
            this.spreadZ = spreadZ;
            this.combinedVolume24h = combinedVolume24h;
        }
    }

    @Inject
    public PairDiscoveryService(FlipService flipService, Config config) {
        this.flipService = Objects.requireNonNull(flipService);
        this.config = Objects.requireNonNull(config);
    }

    /** Latest snapshot of discovered pair candidates. */
    public List<PairCandidate> getCandidates() {
        return lastCandidates;
    }

    @Subscribe
    public void onTick(TickEvent tick) {
        if (!flipService.isReady()) return;

        // 1) Update per-item deviation buffers
        updateDeviationBuffers();

        // 2) Periodically scan for pairs
        tickSinceScan++;
        if (tickSinceScan >= Math.max(1, SCAN_EVERY_TICKS)) {
            tickSinceScan = 0;
            try {
                lastCandidates = discoverPairs();
            } catch (Exception ex) {
                Log.severe("PairDiscoveryService: discovery failed: " + ex.getMessage());
                lastCandidates = List.of();
            }
        }
    }

    // ---------- internals ----------

    private void updateDeviationBuffers() {
        var mapping = flipService.getItemMapping();
        var latest  = flipService.getLatestPrices();
        var day     = flipService.getPrices24h();
        var volumes = flipService.getItemVolumes();

        boolean membersOnly = config.filters.membersOnly;
        int minVolume = Math.max(0, config.filters.minVolume);
        int minBuyLimit = Math.max(0, config.filters.minBuyLimit);
        int maxUnitPrice = Math.max(1, config.risk.maxPricePerUnit);

        // window length taken from pairs config
        int window = Math.max(10, config.strategies.pairsTrading.correlationWindow);

        for (int itemId : flipService.getItemIds()) {
            ItemInfo info = mapping.get(itemId);
            PriceData p = latest.get(itemId);
            FlipService.IntervalData d = day.get(itemId);
            Integer vol = volumes.get(itemId);
            if (info == null || p == null || d == null || vol == null) continue;

            if (membersOnly && !info.members) continue;
            if (vol < minVolume) continue;
            int limit = info.limit != null ? info.limit : Integer.MAX_VALUE;
            if (limit < minBuyLimit) continue;
            if (p.low <= 0 || d.avgLowPrice <= 0) continue;
            if (p.low > maxUnitPrice) continue;

            // Deviation (%) = how far below 24h mean (positive => below mean)
            double devPct = (d.avgLowPrice - p.low) * 100.0 / d.avgLowPrice;

            // Buffer
            DoubleRingBuffer buf = devBuffers.get(itemId);
            if (buf == null || buf.capacity() != window) {
                buf = new DoubleRingBuffer(window); // resize if config changed
                devBuffers.put(itemId, buf);
            }
            buf.add(devPct);
        }
    }

    private List<PairCandidate> discoverPairs() {
        var mapping = flipService.getItemMapping();
        var volumes = flipService.getItemVolumes();

        // 1) Candidate universe: top-N by volume with sufficiently long buffers
        List<Integer> topByVolume = volumes.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(TOP_N_BY_VOLUME)
                .map(Map.Entry::getKey)
                .filter(devBuffers::containsKey)
                .filter(id -> devBuffers.get(id).size() >= Math.max(MIN_SAMPLES_FOR_CORR,
                        Math.min(60, config.strategies.pairsTrading.correlationWindow / 2)))
                .collect(Collectors.toList());

        double minCorr = config.strategies.pairsTrading.minCorrelation;

        List<PairCandidate> out = new ArrayList<>(64);

        // 2) Pairwise correlation scan (O(N^2) on ~300 => ~45k pairs; acceptable every SCAN_EVERY_TICKS)
        for (int i = 0; i < topByVolume.size(); i++) {
            int a = topByVolume.get(i);
            DoubleRingBuffer ba = devBuffers.get(a);
            if (ba == null) continue;

            for (int j = i + 1; j < topByVolume.size(); j++) {
                int b = topByVolume.get(j);
                DoubleRingBuffer bb = devBuffers.get(b);
                if (bb == null) continue;

                int samples = Math.min(ba.size(), bb.size());
                if (samples < MIN_SAMPLES_FOR_CORR) continue;

                double corr = ba.correlation(bb);
                if (corr < minCorr) continue;

                // Stats for output
                double devA = ba.last();
                double devB = bb.last();
                double spread = devA - devB;

                // Optional: keep a small spread buffer only for "passing" pairs to compute a Z-score
                PairTradingService.PairKey key = new PairTradingService.PairKey(a, b);
                DoubleRingBuffer sbuf = spreadBuffers.computeIfAbsent(key, k -> new DoubleRingBuffer(Math.max(30, config.strategies.pairsTrading.correlationWindow)));
                sbuf.add(spread);
                Double z = null;
                double std = sbuf.std();
                if (sbuf.size() >= 10 && std > 0) {
                    z = (spread - sbuf.mean()) / std;
                }

                int combVol = getOrZero(volumes, a) + getOrZero(volumes, b);
                String nameA = mapping.containsKey(a) ? mapping.get(a).name : String.valueOf(a);
                String nameB = mapping.containsKey(b) ? mapping.get(b).name : String.valueOf(b);

                out.add(new PairCandidate(
                        key, nameA, nameB,
                        NumberUtils.round4(corr), samples,
                        NumberUtils.round2(devA), NumberUtils.round2(devB), NumberUtils.round2(spread),
                        (z == null ? null : NumberUtils.round2(z)), combVol
                ));
            }
        }

        // 3) Rank: prefer high correlation, then |spreadZ| if available, then combined volume
        return out.stream()
                .sorted((p1, p2) -> {
                    int c = Double.compare(p2.correlation, p1.correlation);
                    if (c != 0) return c;
                    double z1 = p1.spreadZ == null ? 0 : Math.abs(p1.spreadZ);
                    double z2 = p2.spreadZ == null ? 0 : Math.abs(p2.spreadZ);
                    int zc = Double.compare(z2, z1);
                    if (zc != 0) return zc;
                    return Integer.compare(p2.combinedVolume24h, p1.combinedVolume24h);
                })
                .limit(MAX_OUTPUT_CANDIDATES)
                .collect(Collectors.toUnmodifiableList());
    }

    private static int getOrZero(Map<Integer, Integer> m, int k) { return m.getOrDefault(k, 0); }
}
