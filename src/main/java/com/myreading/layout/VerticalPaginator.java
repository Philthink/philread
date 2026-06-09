package com.myreading.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VerticalPaginator {
    private VerticalPaginator() {
    }

    public static PaginationResult paginate(String chapterId,
                                            List<LayoutUnit> units,
                                            PaginationSettings settings,
                                            String fingerprint) {
        LayoutContext context = new LayoutContext(settings);
        for (LayoutUnit unit : units) {
            switch (unit.getType()) {
                case TEXT:
                    context.placeText(unit);
                    break;
                case RUBY:
                    context.placeRuby(unit);
                    break;
                case IMAGE:
                    context.placeImage(unit);
                    break;
                case BREAK:
                    context.placeBreak(unit.getBreakKind());
                    break;
                default:
                    break;
            }
        }
        context.finish();
        return new PaginationResult(fingerprint, chapterId, context.pages);
    }

    private static final class LayoutContext {
        private final PaginationSettings settings;
        private final double columnWidth;
        private final double columnHeight;
        private final int columnsPerPage;
        private final List<Page> pages = new ArrayList<>();
        private final List<ColumnState> columns = new ArrayList<>();
        private PageState currentPage;
        private ColumnState currentColumn;

        LayoutContext(PaginationSettings settings) {
            this.settings = settings;
            this.columnWidth = Math.max(1.0d, settings.getFontSize() * 1.2d);
            this.columnHeight = Math.max(1.0d, settings.getUsableHeight());
            double usableWidth = Math.max(1.0d, settings.getUsableWidth());
            this.columnsPerPage = Math.max(1, (int) Math.floor((usableWidth + settings.getColumnGap()) / (columnWidth + settings.getColumnGap())));
            startNewPage();
        }

        void placeText(LayoutUnit unit) {
            List<String> tokens = TextTokenizer.tokenize(unit.getText(), unit.isCombineUpright());
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                double advance = TextMetrics.tokenAdvance(token, unit.isCombineUpright(), settings);
                ensureSpaceFor(advance);
                int start = computeTokenStartOffset(tokens, i);
                int end = start + token.length();
                currentColumn.ranges.add(new LayoutRange(unit.getId(), start, end, unit.getType()));
                currentColumn.usedHeight += advance;
            }
        }

        void placeRuby(LayoutUnit unit) {
            double advance = TextMetrics.tokenAdvance(unit.getText(), unit.isCombineUpright(), settings);
            ensureSpaceFor(advance);
            currentColumn.ranges.add(new LayoutRange(unit.getId(), 0, unit.getText() == null ? 0 : unit.getText().length(), unit.getType()));
            currentColumn.usedHeight += advance;
        }

        void placeImage(LayoutUnit unit) {
            double advance = unit.getFixedAdvance() > 0.0d ? unit.getFixedAdvance() : settings.getFontSize() * settings.getLineHeight() * 2.0d;
            ensureSpaceFor(advance);
            currentColumn.ranges.add(new LayoutRange(unit.getId(), 0, 1, unit.getType()));
            currentColumn.usedHeight += advance;
        }

        void placeBreak(LayoutUnit.BreakKind breakKind) {
            if (breakKind == LayoutUnit.BreakKind.COLUMN) {
                startNewColumn();
                return;
            }
            if (breakKind == LayoutUnit.BreakKind.PAGE) {
                startNewPage();
                return;
            }
            double advance = TextMetrics.breakAdvance(breakKind, settings);
            if (currentColumn.usedHeight + advance > columnHeight && currentColumn.usedHeight > 0.0d) {
                startNewColumn();
                if (breakKind == LayoutUnit.BreakKind.LINE) {
                    return;
                }
            }
            currentColumn.usedHeight += advance;
        }

        void finish() {
            flushColumn();
            flushPage();
        }

        private void ensureSpaceFor(double advance) {
            if (currentColumn.usedHeight + advance > columnHeight && currentColumn.usedHeight > 0.0d) {
                startNewColumn();
            }
        }

        private void startNewPage() {
            flushColumn();
            flushPage();
            currentPage = new PageState(pages.size());
            columns.clear();
            startNewColumnInternal();
        }

        private void startNewColumn() {
            flushColumn();
            if (columns.size() >= columnsPerPage) {
                startNewPage();
                return;
            }
            startNewColumnInternal();
        }

        private void startNewColumnInternal() {
            int columnIndex = columns.size();
            double x = settings.getPageWidth() - settings.getMarginRight() - ((columnIndex + 1) * columnWidth) - (columnIndex * settings.getColumnGap());
            double y = settings.getMarginTop();
            currentColumn = new ColumnState(columnIndex, x, y, columnWidth, columnHeight);
            columns.add(currentColumn);
            currentPage.columns.add(currentColumn);
        }

        private void flushColumn() {
            if (currentColumn != null) {
                currentColumn.markFinalized();
            }
        }

        private void flushPage() {
            if (currentPage != null && !currentPage.columns.isEmpty() && currentPage.flushed == false) {
                List<Column> pageColumns = new ArrayList<>();
                for (ColumnState column : currentPage.columns) {
                    if (!column.ranges.isEmpty() || pageColumns.isEmpty()) {
                        pageColumns.add(column.toColumn());
                    }
                }
                pages.add(new Page(currentPage.index, pageColumns));
                currentPage.flushed = true;
            }
        }

        private int computeTokenStartOffset(List<String> tokens, int tokenIndex) {
            int offset = 0;
            for (int i = 0; i < tokenIndex; i++) {
                offset += tokens.get(i).length();
            }
            return offset;
        }
    }

    private static final class PageState {
        private final int index;
        private final List<ColumnState> columns = new ArrayList<>();
        private boolean flushed;

        private PageState(int index) {
            this.index = index;
        }
    }

    private static final class ColumnState {
        private final int index;
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        private final List<LayoutRange> ranges = new ArrayList<>();
        private double usedHeight;
        private boolean finalized;

        private ColumnState(int index, double x, double y, double width, double height) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private void markFinalized() {
            finalized = true;
        }

        private Column toColumn() {
            return new Column(index, x, y, width, height, Collections.unmodifiableList(new ArrayList<>(ranges)));
        }
    }
}

