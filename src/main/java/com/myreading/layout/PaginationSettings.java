package com.myreading.layout;

public final class PaginationSettings {
    private final String fontFamily;
    private final double fontSize;
    private final double lineHeight;
    private final double columnGap;
    private final double pageWidth;
    private final double pageHeight;
    private final double marginTop;
    private final double marginRight;
    private final double marginBottom;
    private final double marginLeft;

    public PaginationSettings(String fontFamily,
                              double fontSize,
                              double lineHeight,
                              double columnGap,
                              double pageWidth,
                              double pageHeight,
                              double marginTop,
                              double marginRight,
                              double marginBottom,
                              double marginLeft) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.lineHeight = lineHeight;
        this.columnGap = columnGap;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.marginTop = marginTop;
        this.marginRight = marginRight;
        this.marginBottom = marginBottom;
        this.marginLeft = marginLeft;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public double getFontSize() {
        return fontSize;
    }

    public double getLineHeight() {
        return lineHeight;
    }

    public double getColumnGap() {
        return columnGap;
    }

    public double getPageWidth() {
        return pageWidth;
    }

    public double getPageHeight() {
        return pageHeight;
    }

    public double getMarginTop() {
        return marginTop;
    }

    public double getMarginRight() {
        return marginRight;
    }

    public double getMarginBottom() {
        return marginBottom;
    }

    public double getMarginLeft() {
        return marginLeft;
    }

    public double getUsableWidth() {
        return Math.max(0.0d, pageWidth - marginLeft - marginRight);
    }

    public double getUsableHeight() {
        return Math.max(0.0d, pageHeight - marginTop - marginBottom);
    }
}

