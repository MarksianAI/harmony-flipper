package com.harmony.flipper.net;

import com.google.gson.Gson;
import com.harmony.flipper.data.ItemMapping;
import com.harmony.flipper.data.Response;
import com.harmony.flipper.data.Time;
import com.harmony.flipper.data.Snapshot;
import com.harmony.flipper.net.transport.OsrsWikiTransport;
import com.harmony.flipper.net.transport.OsrsWikiTransport.RawAggregateResponse;
import com.harmony.flipper.net.transport.OsrsWikiTransport.RawLatestResponse;
import com.harmony.flipper.net.transport.OsrsWikiTransport.RawTimeseriesResponse;
import com.harmony.flipper.net.transport.OsrsWikiTransport.RawVolumeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Public entry point for fetching data from the OSRS Wiki price API.
 */
public final class OsrsWikiClient {

    private final OsrsWikiTransport transport;

    public OsrsWikiClient(String userAgent) {
        this(new OsrsWikiTransport(userAgent));
    }

    public OsrsWikiClient(HttpClient httpClient, Gson gson, URI baseUri, String userAgent, Duration timeout) {
        this(new OsrsWikiTransport(httpClient, gson, baseUri, userAgent, timeout));
    }

    public OsrsWikiClient(OsrsWikiTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Response.Latest getLatest() {
        RawLatestResponse raw = transport.fetchLatest();
        return Response.Latest.fromRaw(raw != null ? raw.data : null);
    }

    public Response.Aggregate getFiveMinutePrices() {
        RawAggregateResponse raw = transport.fetchAggregate("5m");
        long timestamp = raw != null ? raw.timestamp : 0L;
        return Response.Aggregate.fromRaw(timestamp, raw != null ? raw.data : null);
    }

    public Response.Aggregate getOneHourPrices() {
        RawAggregateResponse raw = transport.fetchAggregate("1h");
        long timestamp = raw != null ? raw.timestamp : 0L;
        return Response.Aggregate.fromRaw(timestamp, raw != null ? raw.data : null);
    }

    public Response.Volume getVolumes() {
        RawVolumeResponse raw = transport.fetchVolumes();
        long timestamp = raw != null ? raw.timestamp : 0L;
        return Response.Volume.fromRaw(timestamp, raw != null ? raw.data : null);
    }

    public Response.Timeseries getTimeseries(int itemId, Time.Step step) {
        Objects.requireNonNull(step, "step");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", Integer.toString(itemId));
        params.put("timestep", step.getValue());
        RawTimeseriesResponse raw = transport.fetchTimeseries(params);
        int resolvedId = raw != null ? raw.itemId : itemId;
        return Response.Timeseries.fromRaw(resolvedId, raw != null ? raw.data : null);
    }

    public List<ItemMapping> getMapping() {
        return transport.fetchMapping();
    }

    public Snapshot getFullSnapshot(Iterable<Integer> timeseriesItemIds, Time.Step step) {
        List<Integer> ids = new ArrayList<>();
        if (timeseriesItemIds != null) {
            for (Integer id : timeseriesItemIds) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }

        Response.Latest latest = getLatest();
        Response.Aggregate fiveMinute = getFiveMinutePrices();
        Response.Aggregate oneHour = getOneHourPrices();
        Response.Volume volumes = getVolumes();
        List<ItemMapping> mapping = getMapping();

        Map<Integer, Response.Timeseries> series = new LinkedHashMap<>();
        if (!ids.isEmpty() && step != null) {
            for (Integer id : ids) {
                series.put(id, getTimeseries(id, step));
            }
        }

        return new Snapshot(latest, fiveMinute, oneHour, volumes, mapping, series);
    }
}
