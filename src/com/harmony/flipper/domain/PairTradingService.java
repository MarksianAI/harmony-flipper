package com.harmony.flipper.domain;


import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.harmony.flipper.config.Config;
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
public final class PairTradingService implements Service {

    private final FlipService flipService;
    private final Config config;

    // Registered pairs and state
    private final Map<PairKey, PairState> pairs = new ConcurrentHashMap<>();
    private volatile List<PairSignal> lastSnapshot = List.of();

    public static final class PairKey {
        public final int a;
        public final int b;
        public PairKey(int a, int b) {
            if (a == b) throw new IllegalArgumentException("Pair cannot use same item twice");
            // order to ensure uniqueness
            if (a < b) { this.a = a; this.b = b; } else { this.a = b; this.b = a; }
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PairKey)) return false;
            PairKey pk = (PairKey) o;
            return a == pk.a && b == pk.b;
        }
        @Override public int hashCode() { return Objects.hash(a, b); }
        @Override public String toString() { return a + "-" + b; }
    }

    private static final class PairState {
        final PairKey key;
        final DoubleRingBuffer spreadBuf;   // spread history (devA - devB)
        final DoubleRingBuffer devABuf;     // individual dev histories (optional corr)
        final DoubleRingBuffer devBBuf;
        PairState(PairKey key, int window) {
            this.key = key;
            this.spreadBuf = new DoubleRingBuffer(window);
            this.devABuf = new DoubleRingBuffer(window);
            this.devBBuf = new DoubleRingBuffer(window);
        }
    }

    public static final class PairSignal {
        public final PairKey key;
        public final String nameA;
        public final String nameB;
        public final double devA;      // current deviation vs 24h mean (%)
        public final double devB;
        public final double spread;    // devA - devB
        public final double zScore;
        public final double corr;
        public final boolean entryLongAShortB; // in OSRS: long cheap leg only; this flags which side is "cheap"

        PairSignal(PairKey key, String nameA, String nameB,
                   double devA, double devB, double spread, double zScore, double corr,
                   boolean entryLongAShortB) {
            this.key = key; this.nameA = nameA; this.nameB = nameB;
            this.devA = devA; this.devB = devB; this.spread = spread; this.zScore = zScore; this.corr = corr;
            this.entryLongAShortB = entryLongAShortB;
        }
    }

    @Inject
    public PairTradingService(FlipService flipService, Config config) {
        this.flipService = Objects.requireNonNull(flipService);
        this.config = Objects.requireNonNull(config);
    }

    /** Register a pair to track. (Call from your setup code) */
    public void registerPair(int itemA, int itemB) {
        int window = Math.max(10, config.strategies.pairsTrading.correlationWindow);
        pairs.computeIfAbsent(new PairKey(itemA, itemB), k -> new PairState(k, window));
    }

    /** Remove a pair. */
    public void unregisterPair(int itemA, int itemB) {
        pairs.remove(new PairKey(itemA, itemB));
    }

    /** Current signals snapshot. */
    public List<PairSignal> getSnapshot() {
        return lastSnapshot;
    }

    @Subscribe
    public void onTick(TickEvent tick) {
        if (!config.strategies.pairsTrading.enable) {
            lastSnapshot = List.of();
            return;
        }
        if (!flipService.isReady() || pairs.isEmpty()) {
            lastSnapshot = List.of();
            return;
        }
        try {
            List<PairSignal> sigs = new ArrayList<>(pairs.size());
            var mapping = flipService.getItemMapping();
            var latest  = flipService.getLatestPrices();
            var day     = flipService.getPrices24h();

            double minCorr = config.strategies.pairsTrading.minCorrelation;
            double entryZ  = config.strategies.pairsTrading.entryZScore;
            double exitZ   = config.strategies.pairsTrading.exitZScore;
            int maxActive  = Math.max(1, config.strategies.pairsTrading.maxActivePairs);

            for (PairState st : pairs.values()) {
                int a = st.key.a, b = st.key.b;

                var pA = latest.get(a); var dA = day.get(a);
                var pB = latest.get(b); var dB = day.get(b);
                var iA = mapping.get(a); var iB = mapping.get(b);
                if (pA == null || dA == null || pB == null || dB == null || iA == null || iB == null) continue;
                if (pA.low <= 0 || dA.avgLowPrice <= 0 || pB.low <= 0 || dB.avgLowPrice <= 0) continue;

                // Deviation vs 24h mean (positive = below mean)
                double devA = (dA.avgLowPrice - pA.low) * 100.0 / dA.avgLowPrice;
                double devB = (dB.avgLowPrice - pB.low) * 100.0 / dB.avgLowPrice;

                double spread = devA - devB;
                st.spreadBuf.add(spread);
                st.devABuf.add(devA);
                st.devBBuf.add(devB);

                double mean = st.spreadBuf.mean();
                double std  = st.spreadBuf.std();
                double z = (std > 0) ? (spread - mean) / std : 0.0;

                double corr = st.devABuf.correlation(st.devBBuf);

                if (st.spreadBuf.size() < 10) continue; // wait for buffer

                // Optional correlation gate
                if (corr < minCorr) continue;

                // Entry signal if |z| >= entryZ
                boolean entry = Math.abs(z) >= entryZ;
                if (!entry) continue;

                // Direction: if spread = devA - devB is positive and large, A is "cheaper" (more below its mean).
                boolean longAshortB = z > 0;

                sigs.add(new PairSignal(
                        st.key,
                        iA.name, iB.name,
                        NumberUtils.round2(devA), NumberUtils.round2(devB), NumberUtils.round2(spread),
                        NumberUtils.round2(z), NumberUtils.round2(corr),
                        longAshortB
                ));
            }

            // Limit number of active pair signals
            this.lastSnapshot = sigs.stream()
                    .sorted(Comparator.comparingDouble((PairSignal s) -> Math.abs(s.zScore)).reversed())
                    .limit(maxActive)
                    .collect(Collectors.toUnmodifiableList());

        } catch (Exception ex) {
            Log.severe("PairTradingService: compute failed: " + ex.getMessage());
            lastSnapshot = List.of();
        }
    }
}
