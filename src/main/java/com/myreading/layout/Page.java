package com.myreading.layout;

import java.util.Collections;
import java.util.List;

public final class Page {
    private final int index;
    private final List<Column> columns;

    public Page(int index, List<Column> columns) {
        this.index = index;
        this.columns = Collections.unmodifiableList(columns);
    }

    public int getIndex() {
        return index;
    }

    public List<Column> getColumns() {
        return columns;
    }
}

