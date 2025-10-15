package com.harmony.flipper;


import com.harmony.flipper.config.Config;
import org.rspeer.commons.ArrayUtils;
import org.rspeer.event.Service;
import org.rspeer.game.script.Task;
import org.rspeer.game.script.TaskScript;
import org.rspeer.game.script.meta.ScriptMeta;

@ScriptMeta(
        name = "HarmonyFlipper",
        developer = "Harmony",
        desc = "Flips in harmony",
        version = 1.0,
        model = Config.class
)
public class Flipper extends TaskScript {

    @Override
    public Class<? extends Service>[] getServices() {
        return ArrayUtils.getTypeSafeArray(

        );
    }

    @Override
    public Class<? extends Task>[] tasks() {
        return ArrayUtils.getTypeSafeArray(

        );
    }

}
