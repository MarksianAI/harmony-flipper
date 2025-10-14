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

@Singleton
public final class PairDiscoveryService implements Service {

    private final FlipService flipService;
    private final Config config;

    private final Map<Integer, DoubleRingBuffer> devBuffers = new ConcurrentHashMap<>();
    private final Map<PairTradingService.PairKey, DoubleRingBuffer> spreadBuffers = new ConcurrentHashMap<>();

    private volatile List<PairCandidate> lastCandidates = List.of();

    private static final int TOP_N_BY_VOLUME = 300;
    private static final int SCAN_EVERY_TICKS = 30;
    private static final int MIN_SAMPLES_FOR_CORR = 30;
    private static final int MAX_OUTPUT_CANDIDATES = 100;

    private int tickSinceScan = 0;

    public static final class PairCandidate {
        public final PairTradingService.PairKey key;
        public final String nameA, nameB;
        public final double correlation;
        public final int samples;
        public final double devA, devB, spread;
        public final Double spreadZ;
        public final int combinedVolume24h;

        public PairCandidate(PairTradingService.PairKey key, String nameA, String nameB,
                             double correlation, int samples,
                             double devA, double devB, double spread, Double spreadZ,
                             int combinedVolume24h) {
            this.key = key; this.nameA = nameA; this.nameB = nameB;
            this.correlation = correlation; this.samples = samples;
            this.devA = devA; this.devB = devB; this.spread = spread; this.spreadZ = spreadZ;
            this.combinedVolume24h = combinedVolume24h;
        }
    }

    @Inject
    public PairDiscoveryService(FlipService flipService, Config config) {
        this.flipService = Objects.requireNonNull(flipService);
        this.config = Objects.requireNonNull(config);
    }

    public List<PairCandidate> getCandidates() { return lastCandidates; }

    @Subscribe
    public void onTick(TickEvent tick) {
        if (!flipService.isReady()) return;

        var mapping = flipService.getItemMapping();
        var latest  = flipService.getLatestPrices();
        var day     = flipService.getPrices24h();
        var volumes = flipService.getItemVolumes();

        int window = Math.max(10, config.strategies.pairsTrading.correlationWindow);

        for (int id : flipService.getItemIds()) {
            ItemInfo info = mapping.get(id);
            PriceData p   = latest.get(id);
            FlipService.IntervalData d = day.get(id);
            Integer vol = volumes.get(id);

            if (!basicFilter(info, p, vol)) continue;
            if (d == null || d.avgLowPrice <= 0) continue;

            double devPct = (d.avgLowPrice - p.low) * 100.0 / d.avgLowPrice;

            DoubleRingBuffer buf = devBuffers.computeIfAbsent(id, k -> new DoubleRingBuffer(window));
            if (buf.capacity() != window) devBuffers.put(id, buf = new DoubleRingBuffer(window));
            buf.add(devPct);
        }

        tickSinceScan++;
        if (tickSinceScan < Math.max(1, SCAN_EVERY_TICKS)) return;
        tickSinceScan = 0;

        try {
            lastCandidates = discoverPairs();
        } catch (Exception ex) {
            Log.severe("PairDiscoveryService: discovery failed: " + ex.getMessage());
            lastCandidates = List.of();
        }
    }

    private List<PairCandidate> discoverPairs() {
        var mapping = flipService.getItemMapping();
        var volumes = flipService.getItemVolumes();

        List<Integer> universe = volumes.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(TOP_N_BY_VOLUME)
                .map(Map.Entry::getKey)
                .filter(id -> {
                    DoubleRingBuffer b = devBuffers.get(id);
                    return b != null && b.size() >= Math.max(MIN_SAMPLES_FOR_CORR, config.strategies.pairsTrading.correlationWindow / 2);
                })
                .collect(Collectors.toList());

        double minCorr = config.strategies.pairsTrading.minCorrelation;
        int window = Math.max(30, config.strategies.pairsTrading.correlationWindow);

        List<PairCandidate> out = new ArrayList<>(64);
        for (int i = 0; i < universe.size(); i++) {
            int a = universe.get(i);
            DoubleRingBuffer ba = devBuffers.get(a);
            if (ba == null) continue;

            for (int j = i + 1; j < universe.size(); j++) {
                int b = universe.get(j);
                DoubleRingBuffer bb = devBuffers.get(b);
                if (bb == null) continue;

                int samples = Math.min(ba.size(), bb.size());
                if (samples < MIN_SAMPLES_FOR_CORR) continue;

                double corr = ba.correlation(bb);
                if (corr < minCorr) continue;

                double devA = ba.last();
                double devB = bb.last();
                double spread = devA - devB;

                var key = new PairTradingService.PairKey(a, b);
                DoubleRingBuffer sbuf = spreadBuffers.computeIfAbsent(key, k -> new DoubleRingBuffer(window));
                if (sbuf.capacity() != window) spreadBuffers.put(key, sbuf = new DoubleRingBuffer(window));
                sbuf.add(spread);

                Double z = null;
                if (sbuf.size() >= 10 && sbuf.std() > 0) z = (spread - sbuf.mean()) / sbuf.std();

                int volA = volumes.getOrDefault(a, 0);
                int volB = volumes.getOrDefault(b, 0);
                String nameA = mapping.containsKey(a) ? mapping.get(a).name : String.valueOf(a);
                String nameB = mapping.containsKey(b) ? mapping.get(b).name : String.valueOf(b);

                out.add(new PairCandidate(
                        key, nameA, nameB,
                        NumberUtils.round4(corr), samples,
                        NumberUtils.round2(devA), NumberUtils.round2(devB), NumberUtils.round2(spread),
                        (z == null ? null : NumberUtils.round2(z)), volA + volB
                ));
            }
        }

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

    private boolean basicFilter(ItemInfo info, PriceData p, Integer volume) {
        if (info == null || p == null || volume == null) return false;
        if (config.filters.membersOnly && !info.members) return false;
        if (volume < Math.max(0, config.filters.minVolume)) return false;
        if ((info.limit != null ? info.limit : Integer.MAX_VALUE) < Math.max(0, config.filters.minBuyLimit)) return false;
        if (p.low <= 0 || p.low > Math.max(1, config.risk.maxPricePerUnit)) return false;
        return true;
    }
}
