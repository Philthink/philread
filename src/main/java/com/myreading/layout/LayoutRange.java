package com.myreading.layout;

public final class LayoutRange {
    private final String unitId;
    private final int startOffset;
    private final int endOffset;
    private final LayoutUnit.Type unitType;

    public LayoutRange(String unitId, int startOffset, int endOffset, LayoutUnit.Type unitType) {
        this.unitId = unitId;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.unitType = unitType;
    }

    public String getUnitId() {
        return unitId;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public LayoutUnit.Type getUnitType() {
        return unitType;
    }
}

