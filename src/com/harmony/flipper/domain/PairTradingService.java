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

    private final Map<PairKey, PairState> pairs = new ConcurrentHashMap<>();
    private volatile List<PairSignal> lastSnapshot = List.of();
    
    public static final class PairKey {
        public final int a, b;
        public PairKey(int a, int b) {
            if (a == b) throw new IllegalArgumentException("Pair cannot use same item twice");
            if (a < b) { this.a = a; this.b = b; } else { this.a = b; this.b = a; }
        }
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof PairKey)) return false; PairKey pk = (PairKey) o; return a == pk.a && b == pk.b; }
        @Override public int hashCode() { return Objects.hash(a, b); }
        @Override public String toString() { return a + "-" + b; }
    }

    private static final class PairState {
        final PairKey key;
        final DoubleRingBuffer spreadBuf;
        final DoubleRingBuffer devABuf;
        final DoubleRingBuffer devBBuf;
        final int minSamples;
        PairState(PairKey key, int window, int minSamples) {
            this.key = key;
            this.spreadBuf = new DoubleRingBuffer(window);
            this.devABuf = new DoubleRingBuffer(window);
            this.devBBuf = new DoubleRingBuffer(window);
            this.minSamples = minSamples;
        }
    }

    public static final class PairSignal {
        public final PairKey key;
        public final String nameA, nameB;
        public final double devA, devB, spread, zScore, corr;
        public final boolean entryLongAShortB; // OSRS: long cheap leg; 'short' is conceptual
        PairSignal(PairKey key, String nameA, String nameB,
                   double devA, double devB, double spread, double zScore, double corr, boolean entryLongAShortB) {
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

    public void registerPair(int itemA, int itemB) {
        PairKey key = new PairKey(itemA, itemB);
        pairs.compute(key, (k, existing) -> {
            int window = Math.max(10, config.strategies.pairsTrading.correlationWindow);
            int minSamples = Math.max(window, config.strategies.pairsTrading.correlationWindow);
            if (existing != null
                    && existing.spreadBuf.capacity() == window
                    && existing.minSamples == minSamples) {
                return existing;
            }
            return new PairState(k, window, minSamples);
        });
    }

    public void registerPairs(Collection<PairKey> keys) {
        for (PairKey k : keys) {
            registerPair(k.a, k.b);
        }
    }

    public void unregisterPair(int itemA, int itemB) { pairs.remove(new PairKey(itemA, itemB)); }
    public List<PairSignal> getSnapshot() { return lastSnapshot; }

    @Subscribe
    public void onTick(TickEvent tick) {
        if (!config.strategies.pairsTrading.enable) return;
        if (!flipService.isReady() || pairs.isEmpty()) return;

        try {
            List<PairSignal> sigs = new ArrayList<>(pairs.size());
            for (PairState st : pairs.values()) {
                PairSignal s = buildSignal(st);
                if (s != null) sigs.add(s);
            }
            int maxActive = Math.max(1, config.strategies.pairsTrading.maxActivePairs);
            lastSnapshot = sigs.stream()
                    .sorted(Comparator.comparingDouble((PairSignal s) -> Math.abs(s.zScore)).reversed())
                    .limit(maxActive)
                    .collect(Collectors.toUnmodifiableList());
        } catch (Exception ex) {
            Log.severe("PairTradingService: compute failed: " + ex.getMessage());
            lastSnapshot = List.of();
        }
    }

    private PairSignal buildSignal(PairState st) {
        var mapping = flipService.getItemMapping();
        var latest  = flipService.getLatestPrices();
        var day     = flipService.getPrices24h();
        var volumes = flipService.getItemVolumes();

        int a = st.key.a, b = st.key.b;
        var pA = latest.get(a); var dA = day.get(a); var iA = mapping.get(a); Integer vA = volumes.get(a);
        var pB = latest.get(b); var dB = day.get(b); var iB = mapping.get(b); Integer vB = volumes.get(b);
        if (pA == null || dA == null || pB == null || dB == null || iA == null || iB == null) return null;
        if (pA.low <= 0 || dA.avgLowPrice <= 0 || pB.low <= 0 || dB.avgLowPrice <= 0) return null;

        // Deviation (% below 24h mean). Positive => cheaper vs its mean.
        double devA = (dA.avgLowPrice - pA.low) * 100.0 / dA.avgLowPrice;
        double devB = (dB.avgLowPrice - pB.low) * 100.0 / dB.avgLowPrice;
        double spread = devA - devB;

        st.spreadBuf.add(spread);
        st.devABuf.add(devA);
        st.devBBuf.add(devB);
        if (st.spreadBuf.size() < st.minSamples
                || st.devABuf.size() < st.minSamples
                || st.devBBuf.size() < st.minSamples) {
            return null; // warmup
        }

        double corr = st.devABuf.correlation(st.devBBuf);
        if (corr < config.strategies.pairsTrading.minCorrelation) return null;

        if (Math.abs(spread) < Math.max(0.0, config.strategies.pairsTrading.minNetEdgePercent)) return null;

        double mean = st.spreadBuf.mean();
        double std  = st.spreadBuf.std();
        if (std <= 0) return null;
        double z = (spread - mean) / std;
        if (Math.abs(z) < config.strategies.pairsTrading.entryZScore) return null;

        // Direction: z > 0 => A more below its mean than B, so long A (cheap), short B (rich)
        boolean longAshortB = z > 0;

        // Filter gating on cheap leg (or both if strict disabled)
        if (!legFiltersOK(longAshortB, iA, pA.low, vA, iB, pB.low, vB)) return null;

        // Profit floor on cheap leg: approx expected move back to 24h avg low
        if (!profitFloorOK(longAshortB, pA.low, dA.avgLowPrice, pB.low, dB.avgLowPrice)) return null;

        return new PairSignal(
                st.key, iA.name, iB.name,
                NumberUtils.round2(devA), NumberUtils.round2(devB),
                NumberUtils.round2(spread), NumberUtils.round2(z), NumberUtils.round4(corr),
                longAshortB
        );
    }

    private boolean legFiltersOK(boolean longAshortB,
                                 com.harmony.flipper.data.ItemInfo iA, int lowA, Integer vA,
                                 com.harmony.flipper.data.ItemInfo iB, int lowB, Integer vB) {
        boolean membersOnly = config.filters.membersOnly;
        int minVol  = Math.max(0, config.filters.minVolume);
        int minBuy  = Math.max(0, config.filters.minBuyLimit);
        int maxUnit = Math.max(1, config.risk.maxPricePerUnit);
        boolean cheapOnly = config.strategies.pairsTrading.requireCheapLegPassFilters;

        if (cheapOnly) {
            if (longAshortB) return legPass(iA, lowA, vA, membersOnly, minVol, minBuy, maxUnit);
            return legPass(iB, lowB, vB, membersOnly, minVol, minBuy, maxUnit);
        }
        // Strict off => both must pass
        return legPass(iA, lowA, vA, membersOnly, minVol, minBuy, maxUnit)
            && legPass(iB, lowB, vB, membersOnly, minVol, minBuy, maxUnit);
    }

    private boolean legPass(com.harmony.flipper.data.ItemInfo info, int low, Integer vol,
                            boolean membersOnly, int minVol, int minBuy, int maxUnit) {
        if (membersOnly && !info.members) return false;
        if (vol == null || vol < minVol) return false;
        int limit = info.limit != null ? info.limit : Integer.MAX_VALUE;
        if (limit < minBuy) return false;
        if (low > maxUnit) return false;
        return true;
    }

    private boolean profitFloorOK(boolean longAshortB, int lowA, int avgLowA, int lowB, int avgLowB) {
        int minProfit = Math.max(0, config.strategies.pairsTrading.minNetProfitGp);
        if (minProfit <= 0) return true;
        if (longAshortB) {
            int est = Math.max(0, netSellPrice(avgLowA) - lowA);
            return est >= minProfit;
        } else {
            int est = Math.max(0, netSellPrice(avgLowB) - lowB);
            return est >= minProfit;
        }
    }

    private int netSellPrice(int high) {
        double feeSlipPct = Math.max(0.0, config.risk.feeSlippagePercent);
        return (int) Math.floor(high * (1.0 - feeSlipPct / 100.0));
    }
}
