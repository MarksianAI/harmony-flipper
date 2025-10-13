package com.harmony.flipper.domain;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.harmony.flipper.config.Config;
import com.harmony.flipper.util.NumberUtils;
import com.harmony.flipper.data.ItemInfo;
import com.harmony.flipper.data.PriceData;
import org.rspeer.commons.logging.Log;
import org.rspeer.event.Service;
import org.rspeer.event.Subscribe;
import org.rspeer.game.event.TickEvent;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public final class BidAskService implements Service {

    private final FlipService flipService;
    private final Config config;

    private volatile List<Candidate> lastSnapshot = List.of();

    public static final class Candidate {
        public final int itemId;
        public final String name;
        public final int low;     // insta-sell (our buy)
        public final int high;    // insta-buy (our sell)
        public final int volume24h;
        public final double spreadPct;
        public final double netSpreadPct;
        public final int netProfitPerUnit;
        public final double netRoiPct;
        public final int plannedBuyQty;

        Candidate(int itemId, String name, int low, int high, int volume24h,
                  double spreadPct, double netSpreadPct,
                  int netProfitPerUnit, double netRoiPct, int plannedBuyQty) {
            this.itemId = itemId;
            this.name = name;
            this.low = low;
            this.high = high;
            this.volume24h = volume24h;
            this.spreadPct = spreadPct;
            this.netSpreadPct = netSpreadPct;
            this.netProfitPerUnit = netProfitPerUnit;
            this.netRoiPct = netRoiPct;
            this.plannedBuyQty = plannedBuyQty;
        }
    }

    @Inject
    public BidAskService(FlipService flipService, Config config) {
        this.flipService = Objects.requireNonNull(flipService);
        this.config = Objects.requireNonNull(config);
    }

    @Subscribe
    public void onTick(TickEvent tick) {
        if (!flipService.isReady()) return;

        try {
            List<Candidate> candidates = computeCandidates();
            // Keep only top N by total profit potential (unit * plannedQty)
            int maxPositions = Math.max(1, config.strategies.spread.maxOpenPositions);
            this.lastSnapshot = candidates.stream()
                    .sorted(Comparator.<Candidate>comparingLong(c -> (long) c.netProfitPerUnit * c.plannedBuyQty).reversed())
                    .limit(maxPositions * 2L) // a little buffer
                    .collect(Collectors.toUnmodifiableList());
        } catch (Exception ex) {
            Log.severe("BidAskService: compute failed: " + ex.getMessage());
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

        boolean membersOnly = config.filters.membersOnly;
        int minVolume = Math.max(0, config.filters.minVolume);
        int minBuyLimit = Math.max(0, config.filters.minBuyLimit);

        double minSpreadPct    = Math.max(0.0, config.strategies.spread.minSpreadPercent);
        double minNetSpreadPct = Math.max(0.0, config.strategies.spread.minNetSpreadPercent);
        int minNetProfit       = Math.max(0, config.strategies.spread.minNetProfitGp);
        double minNetRoi       = Math.max(0.0, config.strategies.spread.minNetRoiPercent);

        int maxUnitPrice       = Math.max(1, config.risk.maxPricePerUnit);
        int maxCapPerItem      = Math.max(1, config.risk.maxCapitalPerItem);
        double feeSlipPct      = Math.max(0.0, config.risk.feeSlippagePercent);
        double buyLimitUtilPct = Math.min(100.0, Math.max(1.0, config.risk.buyLimitUtilizationPercent));

        List<Candidate> out = new ArrayList<>(128);

        for (int itemId : flipService.getItemIds()) {
            PriceData p = latest.get(itemId);
            ItemInfo info = mapping.get(itemId);
            Integer volDay = volumes.get(itemId);
            if (p == null || info == null || volDay == null) continue;

            if (membersOnly && !info.members) continue;
            if (volDay < minVolume) continue;

            // Respect buy limit floor (skip super-low limits)
            int buyLimit = info.limit != null ? info.limit : Integer.MAX_VALUE;
            if (buyLimit < minBuyLimit) continue;

            int low = p.low;   // our buy
            int high = p.high; // our sell
            if (low <= 0 || high <= 0 || high <= low) continue;

            if (low > maxUnitPrice) continue;

            double spreadPct = ((high - low) * 100.0) / low;

            // Net-out friction (sale tax + expected slippage/undercuts)
            int netSell = (int) Math.floor(high * (1.0 - feeSlipPct / 100.0));
            int netProfitPerUnit = netSell - low;
            if (netProfitPerUnit <= 0) continue;

            double netRoiPct = (netProfitPerUnit * 100.0) / low;
            double netSpreadPct = ((netSell - low) * 100.0) / low;

            if (spreadPct < minSpreadPct) continue;
            if (netSpreadPct < minNetSpreadPct) continue;
            if (netProfitPerUnit < minNetProfit) continue;
            if (netRoiPct < minNetRoi) continue;

            // Plan quantity under capital + buy-limit utilization
            int qtyByCapital = Math.max(0, maxCapPerItem / Math.max(1, low));
            int qtyByLimit   = (int) Math.floor(buyLimit * (buyLimitUtilPct / 100.0));
            int plannedQty   = Math.min(qtyByCapital, qtyByLimit);
            if (plannedQty <= 0) continue;

            out.add(new Candidate(
                    itemId, info.name, low, high, volDay,
                    NumberUtils.round2(spreadPct), NumberUtils.round2(netSpreadPct),
                    netProfitPerUnit, NumberUtils.round2(netRoiPct), plannedQty
            ));
        }
        return out;
    }
}
