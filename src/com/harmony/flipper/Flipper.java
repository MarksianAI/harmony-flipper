package com.harmony.flipper;


import com.harmony.flipper.config.Config;
import com.harmony.flipper.domain.FlipService;
import com.harmony.flipper.task.BuyTask;
import com.harmony.flipper.task.SellTask;
import org.rspeer.commons.ArrayUtils;
import org.rspeer.event.Service;
import org.rspeer.game.script.Task;
import org.rspeer.game.script.TaskScript;
import org.rspeer.game.script.meta.ScriptMeta;

@ScriptMeta(
        name = "HarmonyFlipper",
        developer = "Harmony",
        desc = "Flip framework using Inubot services",
        version = 1.0,
        model = Config.class
)
public class Flipper extends TaskScript {

    @Override
    public Class<? extends Service>[] getServices() {
        return ArrayUtils.getTypeSafeArray(
                FlipService.class
        );
    }

    @Override
    public Class<? extends Task>[] tasks() {
        return ArrayUtils.getTypeSafeArray(
                BuyTask.class,
                SellTask.class
        );
    }

    @Override
    public void initialize() {
        FlipService flipService = getInjector().getInstance(FlipService.class);
        flipService.preload();
    }
}
