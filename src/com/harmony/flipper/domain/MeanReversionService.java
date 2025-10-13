package com.harmony.flipper.domain;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.harmony.flipper.config.Config;
import com.harmony.flipper.data.ItemInfo;
import com.harmony.flipper.data.PriceData;
import com.harmony.flipper.util.NumberUtils;
import org.rspeer.commons.logging.Log;
import org.rspeer.event.Service;
import org.rspeer.event.Subscribe;
import org.rspeer.game.event.TickEvent;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public final class MeanReversionService implements Service {

    private final FlipService flipService;
    private final Config config;

    private volatile List<Candidate> lastSnapshot = List.of();

    public static final class Candidate {
        public final int itemId;
        public final String name;
        public final int currentLow;
        public final int avgLow24h;
        public final double deviationPct; // positive = below mean
        public final int plannedBuyQty;

        Candidate(int itemId, String name, int currentLow, int avgLow24h, double deviationPct, int plannedBuyQty) {
            this.itemId = itemId;
            this.name = name;
            this.currentLow = currentLow;
            this.avgLow24h = avgLow24h;
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
        if (!config.strategies.meanReversion.enable) {
            lastSnapshot = List.of();
            return;
        }
        if (!flipService.isReady()) return;

        try {
            List<Candidate> candidates = computeCandidates();
            int maxPositions = Math.max(1, config.strategies.meanReversion.maxPositions);
            this.lastSnapshot = candidates.stream()
                    .sorted(Comparator.comparingDouble((Candidate c) -> c.deviationPct).reversed())
                    .limit(maxPositions * 2L)
                    .collect(Collectors.toUnmodifiableList());
        } catch (Exception ex) {
            Log.severe("MeanReversionService: compute failed: " + ex.getMessage());
        }
    }

    public List<Candidate> getSnapshot() {
        return lastSnapshot;
    }

    // ---------- internals ----------

    private List<Candidate> computeCandidates() {
        Map<Integer, ItemInfo> mapping = flipService.getItemMapping();
        Map<Integer, PriceData> latest = flipService.getLatestPrices();
        Map<Integer, Integer> volumes = flipService.getItemVolumes();
        Map<Integer, FlipService.IntervalData> day = flipService.getPrices24h();

        boolean membersOnly = config.filters.membersOnly;
        int minVolume = Math.max(0, config.filters.minVolume);
        int minBuyLimit = Math.max(0, config.filters.minBuyLimit);

        double entryDev = Math.max(0.0, config.strategies.meanReversion.entryDeviationPercent);
        int minProfitGp = Math.max(0, config.strategies.meanReversion.minNetProfitGp); // from our adjusted config
        int maxUnitPrice = Math.max(1, config.risk.maxPricePerUnit);
        int maxCapPerItem = Math.max(1, config.risk.maxCapitalPerItem);
        double buyLimitUtilPct = Math.min(100.0, Math.max(1.0, config.risk.buyLimitUtilizationPercent));

        List<Candidate> out = new ArrayList<>(64);

        for (int itemId : flipService.getItemIds()) {
            ItemInfo info = mapping.get(itemId);
            PriceData p = latest.get(itemId);
            Integer vol = volumes.get(itemId);
            FlipService.IntervalData d = day.get(itemId);
            if (info == null || p == null || vol == null || d == null) continue;

            if (membersOnly && !info.members) continue;
            if (vol < minVolume) continue;
            int buyLimit = info.limit != null ? info.limit : Integer.MAX_VALUE;
            if (buyLimit < minBuyLimit) continue;

            int currentLow = p.low;
            int avgLow24h  = d.avgLowPrice;
            if (currentLow <= 0 || avgLow24h <= 0) continue;
            if (currentLow > maxUnitPrice) continue;

            // Deviation: how far below 24h mean (positive = below mean)
            double deviationPct = (avgLow24h - currentLow) * 100.0 / avgLow24h;
            if (deviationPct < entryDev) continue;

            // Simple profit floor: require at least X gp expected bounce (use exitDeviation as "mean target")
            double exitDev = Math.max(0.0, config.strategies.meanReversion.exitDeviationPercent);
            int targetPrice = (int) Math.floor(avgLow24h * (1.0 - exitDev / 100.0));
            int expProfitPerUnit = targetPrice - currentLow;
            if (expProfitPerUnit < minProfitGp) continue;

            int qtyByCapital = Math.max(0, maxCapPerItem / Math.max(1, currentLow));
            int qtyByLimit   = (int) Math.floor(buyLimit * (buyLimitUtilPct / 100.0));
            int plannedQty   = Math.min(qtyByCapital, qtyByLimit);
            if (plannedQty <= 0) continue;

            out.add(new Candidate(itemId, info.name, currentLow, avgLow24h, NumberUtils.round2(deviationPct), plannedQty));
        }
        return out;
    }
}
