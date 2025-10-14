package com.harmony.flipper.util;

public final class DoubleRingBuffer {

    private final double[] values;
    private int size = 0;
    private int head = 0;

    public DoubleRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.values = new double[capacity];
    }

    public int capacity() {
        return values.length;
    }

    public void add(double value) {
        values[head] = value;
        head = (head + 1) % values.length;
        if (size < values.length) {
            size++;
        }
    }

    public int size() {
        return size;
    }

    public double mean() {
        return mean(size);
    }

    public double mean(int samples) {
        int n = Math.min(Math.max(samples, 0), size);
        if (n == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += getFromEnd(i);
        }
        return sum / n;
    }

    public double std() {
        return std(size);
    }

    public double std(int samples) {
        int n = Math.min(Math.max(samples, 0), size);
        if (n < 2) {
            return 0.0;
        }
        double mean = mean(n);
        double sumSq = 0.0;
        for (int i = 0; i < n; i++) {
            double delta = getFromEnd(i) - mean;
            sumSq += delta * delta;
        }
        return Math.sqrt(sumSq / (n - 1));
    }

    public double correlation(DoubleRingBuffer other) {
        int n = Math.min(this.size, other.size);
        if (n < 2) {
            return 0.0;
        }

        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            double x = getFromEnd(i);
            double y = other.getFromEnd(i);
            meanX += x;
            meanY += y;
        }
        meanX /= n;
        meanY /= n;

        double numerator = 0.0;
        double denomX = 0.0;
        double denomY = 0.0;
        for (int i = 0; i < n; i++) {
            double x = getFromEnd(i) - meanX;
            double y = other.getFromEnd(i) - meanY;
            numerator += x * y;
            denomX += x * x;
            denomY += y * y;
        }
        if (denomX == 0.0 || denomY == 0.0) {
            return 0.0;
        }
        return numerator / Math.sqrt(denomX * denomY);
    }

    public double last() {
        if (size == 0) {
            return 0.0;
        }
        int idx = head - 1;
        if (idx < 0) {
            idx += values.length;
        }
        return values[idx];
    }

    private double getFromEnd(int idxFromEnd) {
        int idx = (head - 1 - idxFromEnd) % values.length;
        if (idx < 0) {
            idx += values.length;
        }
        return values[idx];
    }
}
