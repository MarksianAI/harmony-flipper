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
public final class BidAskService implements Service {

    private final FlipService flipService;
    private final Config config;
    private volatile List<Candidate> lastSnapshot = List.of();

    public static final class Candidate {
        public final int itemId;
        public final String name;
        public final int low;
        public final int high;
        public final int volume24h;
        public final double spreadPct;
        public final double netSpreadPct;
        public final int netProfitPerUnit;
        public final double netRoiPct;
        public final int plannedBuyQty;

        Candidate(int itemId, String name, int low, int high, int volume24h,
                  double spreadPct, double netSpreadPct, int netProfitPerUnit, double netRoiPct,
                  int plannedBuyQty) {
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
            List<Candidate> rows = new ArrayList<>();
            for (int id : flipService.getItemIds()) {
                Candidate c = buildCandidate(id);
                if (c != null) rows.add(c);
            }
            int maxPositions = Math.max(1, config.strategies.spread.maxOpenPositions);
            lastSnapshot = rows.stream()
                    .sorted(Comparator.<Candidate>comparingLong(c -> (long) c.netProfitPerUnit * c.plannedBuyQty).reversed())
                    .limit(maxPositions * 2L)
                    .collect(Collectors.toUnmodifiableList());
        } catch (Exception ex) {
            Log.severe("BidAskService: compute failed: " + ex.getMessage());
        }
    }

    public List<Candidate> getSnapshot() { return lastSnapshot; }

    private Candidate buildCandidate(int itemId) {
        var mapping = flipService.getItemMapping();
        var latest  = flipService.getLatestPrices();
        var volumes = flipService.getItemVolumes();

        ItemInfo info = mapping.get(itemId);
        PriceData p   = latest.get(itemId);
        Integer vol   = volumes.get(itemId);

        if (info == null || p == null || vol == null) return null;
        if (!passesFilters(info, vol, p.low)) return null;

        int low  = p.low;
        int high = p.high;
        if (high <= low) return null;

        double rawSpreadPct = pct(high - low, low);
        int netSell = netSellPrice(high);
        int netPer  = netSell - low;
        if (netPer <= 0) return null;

        double netSpreadPct = pct(netSell - low, low);
        double netRoiPct    = pct(netPer, low);
        if (!passesPnLGates(rawSpreadPct, netSpreadPct, netPer, netRoiPct)) return null;

        int plannedQty = plannedQuantity(low, info.limit);
        if (plannedQty <= 0) return null;

        return new Candidate(
                itemId, info.name, low, high, vol,
                NumberUtils.round2(rawSpreadPct),
                NumberUtils.round2(netSpreadPct),
                netPer,
                NumberUtils.round2(netRoiPct),
                plannedQty
        );
    }

    private boolean passesFilters(ItemInfo info, int volume24h, int unitPrice) {
        if (config.filters.membersOnly && !info.members) return false;
        if (volume24h < Math.max(0, config.filters.minVolume)) return false;
        int limit = info.limit != null ? info.limit : Integer.MAX_VALUE;
        if (limit < Math.max(0, config.filters.minBuyLimit)) return false;
        if (unitPrice > Math.max(1, config.risk.maxPricePerUnit)) return false;
        return true;
    }

    private boolean passesPnLGates(double spreadPct, double netSpreadPct, int netPerUnit, double netRoiPct) {
        if (spreadPct < Math.max(0.0, config.strategies.spread.minSpreadPercent)) return false;
        if (netSpreadPct < Math.max(0.0, config.strategies.spread.minNetSpreadPercent)) return false;
        if (netPerUnit < Math.max(0, config.strategies.spread.minNetProfitGp)) return false;
        if (netRoiPct < Math.max(0.0, config.strategies.spread.minNetRoiPercent)) return false;
        return true;
    }

    private int plannedQuantity(int unitPrice, Integer buyLimitNullable) {
        int maxCap     = Math.max(1, config.risk.maxCapitalPerItem);
        double utilPct = clamp(config.risk.buyLimitUtilizationPercent, 1.0, 100.0);
        int limit      = buyLimitNullable != null ? buyLimitNullable : Integer.MAX_VALUE;

        int byCapital  = Math.max(0, maxCap / Math.max(1, unitPrice));
        int byLimit    = (int) Math.floor(limit * (utilPct / 100.0));
        return Math.min(byCapital, byLimit);
    }

    private int netSellPrice(int high) {
        double feeSlipPct = Math.max(0.0, config.risk.feeSlippagePercent);
        return (int) Math.floor(high * (1.0 - feeSlipPct / 100.0));
    }

    private static double pct(int num, int denom) {
        if (denom <= 0) return 0.0;
        return (num * 100.0) / denom;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
