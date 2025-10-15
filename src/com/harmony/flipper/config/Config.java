package com.harmony.flipper.config;

import com.google.inject.Singleton;
import org.rspeer.game.script.model.ConfigModel;
import org.rspeer.game.script.model.ui.schema.structure.Section;
import org.rspeer.game.script.model.ui.schema.text.TextFieldComponent;
import org.rspeer.game.script.model.ui.schema.text.TextInputType;

@Singleton
public class Config extends ConfigModel {

    @Section("Risk")
    public final Risk risk = new Risk();

    @Section("Advanced")
    public final Advanced advanced = new Advanced();

    public static class Risk extends ConfigModel {

        @TextFieldComponent(name = "Max Unit Price (gp per unit)", key = "max_unit_price_gp", inputType = TextInputType.NUMERIC)
        public int maxUnitPriceGp = 5_000_000;

        @TextFieldComponent(name = "Max Item Capital (total gp per item)", key = "max_item_capital_gp", inputType = TextInputType.NUMERIC)
        public int maxItemCapitalGp = 10_000_000;
    }

    public static class Advanced extends ConfigModel {

        @TextFieldComponent(name = "HTTP User-Agent", key = "user_agent", inputType = TextInputType.ANY)
        public String userAgent = "HarmonyFlipper/0.1 (contact@example.com)";
    }
}
