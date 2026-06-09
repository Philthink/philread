package com.myreading.layout;

public final class LayoutUnit {
    public enum Type {
        TEXT,
        RUBY,
        IMAGE,
        BREAK
    }

    public enum BreakKind {
        LINE,
        BLOCK,
        COLUMN,
        PAGE
    }

    private final String id;
    private final Type type;
    private final String text;
    private final String annotation;
    private final String resourceHref;
    private final boolean combineUpright;
    private final BreakKind breakKind;
    private final double fixedAdvance;

    private LayoutUnit(String id,
                       Type type,
                       String text,
                       String annotation,
                       String resourceHref,
                       boolean combineUpright,
                       BreakKind breakKind,
                       double fixedAdvance) {
        this.id = id;
        this.type = type;
        this.text = text;
        this.annotation = annotation;
        this.resourceHref = resourceHref;
        this.combineUpright = combineUpright;
        this.breakKind = breakKind;
        this.fixedAdvance = fixedAdvance;
    }

    public static LayoutUnit text(String id, String text, boolean combineUpright) {
        return new LayoutUnit(id, Type.TEXT, text, null, null, combineUpright, null, 0.0d);
    }

    public static LayoutUnit ruby(String id, String baseText, String annotation, boolean combineUpright) {
        return new LayoutUnit(id, Type.RUBY, baseText, annotation, null, combineUpright, null, 0.0d);
    }

    public static LayoutUnit image(String id, String resourceHref, double fixedAdvance) {
        return new LayoutUnit(id, Type.IMAGE, null, null, resourceHref, false, null, fixedAdvance);
    }

    public static LayoutUnit breakUnit(String id, BreakKind breakKind) {
        return new LayoutUnit(id, Type.BREAK, null, null, null, false, breakKind, 0.0d);
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public String getAnnotation() {
        return annotation;
    }

    public String getResourceHref() {
        return resourceHref;
    }

    public boolean isCombineUpright() {
        return combineUpright;
    }

    public BreakKind getBreakKind() {
        return breakKind;
    }

    public double getFixedAdvance() {
        return fixedAdvance;
    }
}

