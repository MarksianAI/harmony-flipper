package com.harmony.flipper.config;

import com.google.inject.Singleton;
import org.rspeer.game.script.model.ConfigModel;
import org.rspeer.game.script.model.ui.schema.checkbox.CheckBoxComponent;
import org.rspeer.game.script.model.ui.schema.structure.Section;
import org.rspeer.game.script.model.ui.schema.text.TextFieldComponent;
import org.rspeer.game.script.model.ui.schema.text.TextInputType;

@Singleton
public class Config extends ConfigModel {

    // ===== Top-level sections =================================================

    @Section("Flipping Filters")
    public final Filters filters = new Filters();

    @Section("Strategies")
    public final Strategies strategies = new Strategies();

    @Section("Risk / Limits")
    public final RiskLimits risk = new RiskLimits();

    @Section("Advanced")
    public final Advanced advanced = new Advanced();


    // ===== Filters: item-universe pre-screens only ============================

    public static class Filters extends ConfigModel {

        // Keep Filters about *items*, not PnL/risk.
        // Removed: global Min ROI (strategy-specific) and Max Buy Price (moved to Risk).

        @CheckBoxComponent(name = "Members items only", key = "members_only")
        public boolean membersOnly = false;

        @TextFieldComponent(name = "Min Volume (24h est.)", key = "min_vol", inputType = TextInputType.NUMERIC)
        public int minVolume = 5_000;

        // Ignore items with very low 4h buy limits
        @TextFieldComponent(name = "Min Buy Limit (per 4h)", key = "min_buy_limit", inputType = TextInputType.NUMERIC)
        public int minBuyLimit = 50;
    }


    // ===== Strategies ==========================================================

    public static class Strategies extends ConfigModel {

        @Section("Bid-Ask Spread")
        public final SpreadStrategy spread = new SpreadStrategy();

        @Section("Mean Reversion")
        public final MeanReversionStrategy meanReversion = new MeanReversionStrategy();

        @Section("Pairs Trading")
        public final PairsTradingStrategy pairsTrading = new PairsTradingStrategy();
    }


    // --- Spread (flip) ---------------------------------------------------------

    public static class SpreadStrategy extends ConfigModel {

        // Screening on quoted spread (gross and net)
        @TextFieldComponent(name = "Min Spread (%)", key = "spread_min_pct", inputType = TextInputType.NUMERIC)
        public double minSpreadPercent = 2.0;

        @TextFieldComponent(name = "Min Net Spread (%)", key = "spread_min_net_pct", inputType = TextInputType.NUMERIC)
        public double minNetSpreadPercent = 1.2;

        // Per-strategy profit/ROI thresholds (moved from Filters)
        @TextFieldComponent(name = "Min Net Profit (gp)", key = "spread_min_profit_gp", inputType = TextInputType.NUMERIC)
        public int minNetProfitGp = 1_500;

        @TextFieldComponent(name = "Min ROI (net, %)", key = "spread_min_roi_net", inputType = TextInputType.NUMERIC)
        public double minNetRoiPercent = 2.5;

        // Position management
        @TextFieldComponent(name = "Max Hold Time (min)", key = "spread_max_hold_min", inputType = TextInputType.NUMERIC)
        public int maxHoldMinutes = 120;

        // 1 item ≈ 2 slots (buy + sell); 4 items ≈ 8 slots.
        @TextFieldComponent(name = "Max Open Positions", key = "spread_max_positions", inputType = TextInputType.NUMERIC)
        public int maxOpenPositions = 4;

        // Avoid undercut-churn
        @TextFieldComponent(name = "Reprice Cooldown (min)", key = "spread_reprice_cooldown_min", inputType = TextInputType.NUMERIC)
        public int repriceCooldownMinutes = 15;
    }


    // --- Mean reversion --------------------------------------------------------

    public static class MeanReversionStrategy extends ConfigModel {

        @CheckBoxComponent(name = "Enable Mean Reversion", key = "mr_enable")
        public boolean enable = false;

        @TextFieldComponent(name = "Lookback (ticks)", key = "mr_lookback_ticks", inputType = TextInputType.NUMERIC)
        public int lookbackTicks = 240;

        @TextFieldComponent(name = "Entry Deviation (%)", key = "mr_entry_deviation_pct", inputType = TextInputType.NUMERIC)
        public double entryDeviationPercent = 2.0;

        @TextFieldComponent(name = "Exit Deviation (%)", key = "mr_exit_deviation_pct", inputType = TextInputType.NUMERIC)
        public double exitDeviationPercent = 0.8;

        // Optional Bollinger-band entry
        @CheckBoxComponent(name = "Use Bollinger Bands", key = "mr_use_bb")
        public boolean useBollinger = true;

        @TextFieldComponent(name = "BB Lookback (ticks)", key = "mr_bb_lookback", inputType = TextInputType.NUMERIC)
        public int bbLookbackTicks = 120;

        @TextFieldComponent(name = "BB Std Dev", key = "mr_bb_stddev", inputType = TextInputType.NUMERIC)
        public double bbStdDevs = 2.0;

        // Per-strategy min profit (since MR fills/holds tend to be slower than flips)
        @TextFieldComponent(name = "Min Net Profit (gp)", key = "mr_min_profit_gp", inputType = TextInputType.NUMERIC)
        public int minNetProfitGp = 2_000;

        @TextFieldComponent(name = "Max Concurrent Positions", key = "mr_max_positions", inputType = TextInputType.NUMERIC)
        public int maxPositions = 3;

        @TextFieldComponent(name = "Re-entry Cooldown (min)", key = "mr_reentry_cooldown_min", inputType = TextInputType.NUMERIC)
        public int reentryCooldownMinutes = 60;
    }


    // --- Pairs trading (relative value) ---------------------------------------

    public static class PairsTradingStrategy extends ConfigModel {

        @CheckBoxComponent(name = "Enable Pairs Trading", key = "pairs_enable")
        public boolean enable = false;

        @TextFieldComponent(name = "Correlation Window (ticks)", key = "pairs_corr_window", inputType = TextInputType.NUMERIC)
        public int correlationWindow = 240;

        @TextFieldComponent(name = "Min Correlation", key = "pairs_min_corr", inputType = TextInputType.NUMERIC)
        public double minCorrelation = 0.90;

        @TextFieldComponent(name = "Spread Entry Z-Score", key = "pairs_entry_z", inputType = TextInputType.NUMERIC)
        public double entryZScore = 2.0;

        @TextFieldComponent(name = "Spread Exit Z-Score", key = "pairs_exit_z", inputType = TextInputType.NUMERIC)
        public double exitZScore = 0.7;

        @TextFieldComponent(name = "Rebalance Cooldown (ticks)", key = "pairs_rebalance_ticks", inputType = TextInputType.NUMERIC)
        public int rebalanceCooldownTicks = 60;

        @TextFieldComponent(name = "Max Active Pairs", key = "pairs_max_active", inputType = TextInputType.NUMERIC)
        public int maxActivePairs = 2;

        // Long-only guard (no shorting in OSRS)
        @CheckBoxComponent(name = "Require Cheap Leg Pass Filters", key = "pairs_require_filters")
        public boolean requireCheapLegPassFilters = true;

        // Per-strategy notion of "edge" (convergence margin) and absolute PnL floor
        @TextFieldComponent(name = "Min Edge (net, %)", key = "pairs_min_edge_pct", inputType = TextInputType.NUMERIC)
        public double minNetEdgePercent = 1.0;

        @TextFieldComponent(name = "Min Net Profit (gp)", key = "pairs_min_profit_gp", inputType = TextInputType.NUMERIC)
        public int minNetProfitGp = 1_500;
    }


    // ===== Risk / Limits =======================================================

    public static class RiskLimits extends ConfigModel {

        // Capital guards
        @TextFieldComponent(name = "Max Price / Unit (gp)", key = "risk_max_price_per_unit", inputType = TextInputType.NUMERIC)
        public int maxPricePerUnit = 5_000_000; // moved from Filters.maxPrice

        @TextFieldComponent(name = "Max Capital / Item (gp)", key = "risk_max_cap_item", inputType = TextInputType.NUMERIC)
        public int maxCapitalPerItem = 10_000_000;

        @TextFieldComponent(name = "Max Capital / Strategy (%)", key = "risk_max_cap_strategy", inputType = TextInputType.NUMERIC)
        public double maxCapitalPerStrategyPercent = 60.0;

        // Buy limits / slots
        @TextFieldComponent(name = "Buy Limit Utilization (%)", key = "risk_buy_limit_util", inputType = TextInputType.NUMERIC)
        public double buyLimitUtilizationPercent = 80.0;

        @TextFieldComponent(name = "Reserve Sell Slots", key = "risk_reserve_sell_slots", inputType = TextInputType.NUMERIC)
        public int reserveSellSlots = 2;

        // Friction and protection
        @TextFieldComponent(name = "Fee + Slippage (%)", key = "risk_fee_slippage_pct", inputType = TextInputType.NUMERIC)
        public double feeSlippagePercent = 1.2;

        @TextFieldComponent(name = "Stop-Loss (%)", key = "risk_soft_sl_pct", inputType = TextInputType.NUMERIC)
        public double softStopLossPercent = 6.0;
    }


    // ===== Advanced ============================================================

    public static class Advanced extends ConfigModel {

        @TextFieldComponent(name = "Tick throttle (update every N ticks)", key = "tick_throttle", inputType = TextInputType.NUMERIC)
        public int tickThrottle = 12;

        @TextFieldComponent(name = "HTTP User-Agent", key = "ua", inputType = TextInputType.ANY)
        public String userAgent = "InuFlipper/1.1 (contact: you@example.com; purpose: OSRS research)";
    }
}
