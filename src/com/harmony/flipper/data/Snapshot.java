package com.harmony.flipper.data;

import com.harmony.flipper.data.Response.Aggregate;
import com.harmony.flipper.data.Response.Latest;
import com.harmony.flipper.data.Response.Timeseries;
import com.harmony.flipper.data.Response.Volume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Snapshot {

    private final Latest latest;
    private final Aggregate fiveMinute;
    private final Aggregate oneHour;
    private final Volume volumes;
    private final List<ItemMapping> mapping;
    private final Map<Integer, Timeseries> timeseries;

    public Snapshot(Latest latest,
                    Aggregate fiveMinute,
                    Aggregate oneHour,
                    Volume volumes,
                    List<ItemMapping> mapping,
                    Map<Integer, Timeseries> timeseries) {
        this.latest = latest;
        this.fiveMinute = fiveMinute;
        this.oneHour = oneHour;
        this.volumes = volumes;
        this.mapping = mapping == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(mapping));
        this.timeseries = timeseries == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(timeseries));
    }

    public Latest getLatest() {
        return latest;
    }

    public Aggregate getFiveMinute() {
        return fiveMinute;
    }

    public Aggregate getOneHour() {
        return oneHour;
    }

    public Volume getVolumes() {
        return volumes;
    }

    public List<ItemMapping> getMapping() {
        return mapping;
    }

    public Map<Integer, Timeseries> getTimeseries() {
        return timeseries;
    }
}
