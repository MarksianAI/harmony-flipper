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
public final class MeanReversionService implements Service {

    private final FlipService flipService;
    private final Config config;
    private final Map<Integer, DoubleRingBuffer> lowPriceBuffers = new ConcurrentHashMap<>();
    private volatile List<Candidate> lastSnapshot = List.of();

    public static final class Candidate {
        public final int itemId;
        public final String name;
        public final int currentLow;
        public final double baseline;
        public final double deviationPct;
        public final int plannedBuyQty;

        Candidate(int itemId, String name, int currentLow, double baseline, double deviationPct, int plannedBuyQty) {
            this.itemId = itemId;
            this.name = name;
            this.currentLow = currentLow;
            this.baseline = baseline;
            this.deviationPct = deviationPct;
            this.plannedBuyQty = plannedBuyQty;
        }
    }

    @Inject
    public MeanReversionService(FlipService flipService, Config config) {
        this.flipService = Objects.requireNonNull(flipService);
        this.config = Objects.requireNonNull(config);
    }

    @Subscribe
    public void onTick(TickEvent tick) {
        if (!config.strategies.meanReversion.enable) return;
        if (!flipService.isReady()) return;

        var latest = flipService.getLatestPrices();
        var items  = flipService.getItemMapping();
        int lookback = Math.max(5, config.strategies.meanReversion.lookbackTicks);
        int bbLook   = Math.max(5, config.strategies.meanReversion.bbLookbackTicks);
        int cap      = Math.max(lookback, bbLook);
        for (var e : latest.entrySet()) {
            int id = e.getKey();
            PriceData p = e.getValue();
            if (p == null || p.low <= 0) continue;
            if (!items.containsKey(id)) continue;

            DoubleRingBuffer buf = lowPriceBuffers.computeIfAbsent(id, k -> new DoubleRingBuffer(cap));
            if (buf.capacity() != cap) lowPriceBuffers.put(id, buf = new DoubleRingBuffer(cap));
            buf.add(p.low);
        }

        try {
            List<Candidate> rows = new ArrayList<>();
            for (int id : flipService.getItemIds()) {
                Candidate c = candidateFor(id);
                if (c != null) rows.add(c);
            }
            int maxPositions = Math.max(1, config.strategies.meanReversion.maxPositions);
            lastSnapshot = rows.stream()
                    .sorted(Comparator.comparingDouble((Candidate c) -> c.deviationPct).reversed())
                    .limit(maxPositions * 2L)
                    .collect(Collectors.toUnmodifiableList());
        } catch (Exception ex) {
            Log.severe("MeanReversionService: compute failed: " + ex.getMessage());
        }
    }

    public List<Candidate> getSnapshot() { return lastSnapshot; }

    private Candidate candidateFor(int itemId) {
        var mapping = flipService.getItemMapping();
        var latest  = flipService.getLatestPrices();
        var volumes = flipService.getItemVolumes();
        var day     = flipService.getPrices24h();

        ItemInfo info = mapping.get(itemId);
        PriceData p   = latest.get(itemId);
        Integer vol   = volumes.get(itemId);
        if (info == null || p == null || vol == null) return null;
        if (!passesFilters(info, vol, p.low)) return null;

        Baseline bl = computeBaseline(itemId, p.low, day.get(itemId));
        if (bl == null) return null;
        if (bl.deviationPct < Math.max(0.0, config.strategies.meanReversion.entryDeviationPercent)) return null;

        int target = (int) Math.floor(bl.baseline * (1.0 - Math.max(0.0, config.strategies.meanReversion.exitDeviationPercent) / 100.0));
        int expPer = target - p.low;
        if (expPer < Math.max(0, config.strategies.meanReversion.minNetProfitGp)) return null;

        int planned = plannedQuantity(p.low, info.limit);
        if (planned <= 0) return null;

        return new Candidate(itemId, info.name, p.low, NumberUtils.round2(bl.baseline),
                NumberUtils.round2(bl.deviationPct), planned);
    }

    private boolean passesFilters(ItemInfo info, int vol, int unitPrice) {
        if (config.filters.membersOnly && !info.members) return false;
        if (vol < Math.max(0, config.filters.minVolume)) return false;
        int limit = info.limit != null ? info.limit : Integer.MAX_VALUE;
        if (limit < Math.max(0, config.filters.minBuyLimit)) return false;
        if (unitPrice <= 0 || unitPrice > Math.max(1, config.risk.maxPricePerUnit)) return false;
        return true;
    }

    private int plannedQuantity(int unitPrice, Integer limitNullable) {
        int maxCap = Math.max(1, config.risk.maxCapitalPerItem);
        double util = Math.max(1.0, Math.min(100.0, config.risk.buyLimitUtilizationPercent));
        int limit = limitNullable != null ? limitNullable : Integer.MAX_VALUE;
        int byCap = Math.max(0, maxCap / Math.max(1, unitPrice));
        int byLim = (int) Math.floor(limit * (util / 100.0));
        return Math.min(byCap, byLim);
    }

    private static final class Baseline {
        final double baseline; final double deviationPct;
        Baseline(double baseline, double deviationPct) { this.baseline = baseline; this.deviationPct = deviationPct; }
    }

    private Baseline computeBaseline(int itemId, int currentLow, FlipService.IntervalData day) {
        DoubleRingBuffer buf = lowPriceBuffers.get(itemId);
        boolean useBB = config.strategies.meanReversion.useBollinger;
        int bbLook = Math.max(5, config.strategies.meanReversion.bbLookbackTicks);
        int smaLook = Math.max(5, config.strategies.meanReversion.lookbackTicks);

        if (useBB && buf != null) {
            int bbSamples = Math.min(bbLook, buf.size());
            if (bbSamples >= 5) {
                double sma = buf.mean(bbSamples);
                double std = buf.std(bbSamples);
                double lowerBand = sma - Math.max(0.5, config.strategies.meanReversion.bbStdDevs) * std;
                double entryPct = Math.max(0.0, config.strategies.meanReversion.entryDeviationPercent);
                double entryThreshold = Math.max(lowerBand, sma * (1.0 - entryPct / 100.0));
                if (currentLow > entryThreshold) return null;
                return new Baseline(sma, pct(sma - currentLow, sma));
            }
        }

        if (buf != null) {
            int smaSamples = Math.min(smaLook, buf.size());
            if (smaSamples >= 5) {
                double sma = buf.mean(smaSamples);
                return new Baseline(sma, pct(sma - currentLow, sma));
            }
        }

        if (day == null || day.avgLowPrice <= 0) return null;
        double base = day.avgLowPrice;
        return new Baseline(base, pct(base - currentLow, base));
    }

    private static double pct(double num, double denom) {
        if (denom <= 0) return 0.0;
        return (num * 100.0) / denom;
    }
}
