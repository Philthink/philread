package com.myreading.layout;

import java.util.Collections;
import java.util.List;

public final class Column {
    private final int index;
    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final List<LayoutRange> ranges;

    public Column(int index, double x, double y, double width, double height, List<LayoutRange> ranges) {
        this.index = index;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.ranges = Collections.unmodifiableList(ranges);
    }

    public int getIndex() {
        return index;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public List<LayoutRange> getRanges() {
        return ranges;
    }
}

