package com.myreading.tests;

import com.myreading.epub.model.Book;
import com.myreading.epub.model.Chapter;
import com.myreading.epub.model.Resource;
import com.myreading.epub.parse.EpubParser;
import com.myreading.layout.ChapterLayoutBuilder;
import com.myreading.layout.LayoutUnit;
import com.myreading.layout.Page;
import com.myreading.layout.PaginationCache;
import com.myreading.layout.PaginationResult;
import com.myreading.layout.PaginationService;
import com.myreading.layout.PaginationSettings;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SmokeTests {
    public static void main(String[] args) throws Exception {
        SmokeTests tests = new SmokeTests();
        tests.testEpubParsing();
        tests.testVerticalPaginationAndCache();
        System.out.println("Smoke tests passed.");
    }

    private void testEpubParsing() throws Exception {
        Path epub = buildSampleEpub();
        Book book = new EpubParser().parse(epub);

        assertEquals("古籍样书", book.getMetadata().getTitle(), "metadata title");
        assertEquals(1, book.getChapters().size(), "chapter count");
        Chapter chapter = book.getChapters().get(0);
        assertEquals("第一章", chapter.getTitle(), "chapter title from nav.xhtml");
        assertTrue(chapter.getContent().contains("天地玄黃"), "chapter content");

        Map<String, Resource> resources = book.getResourcesByHref();
        assertTrue(resources.containsKey("OEBPS/styles/main.css"), "css resource");
        assertTrue(resources.containsKey("OEBPS/fonts/test.otf"), "font resource");
        assertTrue(resources.containsKey("OEBPS/images/pic.png"), "image resource");
        assertTrue(resources.containsKey("OEBPS/vector/fig.svg"), "svg resource");
        assertTrue(resources.containsKey("OEBPS/audio/note.mp3"), "audio resource");

        assertTrue(chapter.getReferencedResourceHrefs().contains("OEBPS/styles/main.css"), "chapter stylesheet reference");
        assertTrue(chapter.getReferencedResourceHrefs().contains("OEBPS/images/pic.png"), "chapter image reference");
        assertTrue(chapter.getReferencedResourceHrefs().contains("OEBPS/vector/fig.svg"), "chapter svg reference");
        assertTrue(chapter.getReferencedResourceHrefs().contains("OEBPS/audio/note.mp3"), "chapter audio reference");
        assertTrue(chapter.getReferencedResourceHrefs().contains("OEBPS/fonts/test.otf"), "chapter font reference");
    }

    private void testVerticalPaginationAndCache() throws Exception {
        Path epub = buildSampleEpub();
        Book book = new EpubParser().parse(epub);
        Chapter chapter = book.getChapters().get(0);

        ChapterLayoutBuilder builder = new ChapterLayoutBuilder();
        List<LayoutUnit> units = new ArrayList<>(builder.build(chapter.getId(), chapter.getContent()));
        units.add(LayoutUnit.text(chapter.getId() + "-combine", "2026", true));

        PaginationSettings compact = new PaginationSettings(
                "Source Han Serif",
                20.0d,
                1.25d,
                18.0d,
                360.0d,
                480.0d,
                24.0d,
                24.0d,
                24.0d,
                24.0d);

        PaginationSettings large = new PaginationSettings(
                "Source Han Serif",
                24.0d,
                1.35d,
                18.0d,
                360.0d,
                480.0d,
                24.0d,
                24.0d,
                24.0d,
                24.0d);

        PaginationCache cache = new PaginationCache(8);
        PaginationService service = new PaginationService(cache);

        PaginationResult first = service.paginate(chapter.getId(), units, compact);
        PaginationResult second = service.paginate(chapter.getId(), units, compact);
        PaginationResult third = service.paginate(chapter.getId(), units, large);

        assertTrue(first == second, "cache hit should reuse the same PaginationResult instance");
        assertTrue(!first.getFingerprint().equals(third.getFingerprint()), "layout fingerprint should change when font settings change");
        assertTrue(first.getPages().size() >= 2, "compact pagination should span multiple pages");

        Page firstPage = first.getPages().get(0);
        assertTrue(!firstPage.getColumns().isEmpty(), "page should contain at least one column");
        if (firstPage.getColumns().size() > 1) {
            double rightMostX = firstPage.getColumns().get(0).getX();
            double leftColumnX = firstPage.getColumns().get(1).getX();
            assertTrue(rightMostX > leftColumnX, "columns should progress from right to left");
        }
    }

    private Path buildSampleEpub() throws IOException {
        Path epub = Files.createTempFile("myreading-smoke", ".epub");
        try (OutputStream fileOut = Files.newOutputStream(epub);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            put(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            put(zip, "META-INF/container.xml", containerXml().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/content.opf", opf().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/nav.xhtml", navXhtml().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/toc.ncx", tocNcx().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/chapter1.xhtml", chapterXhtml().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/styles/main.css", css().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/fonts/test.otf", new byte[]{0x00, 0x01, 0x02, 0x03});
            put(zip, "OEBPS/images/pic.png", new byte[]{0x11, 0x22, 0x33, 0x44});
            put(zip, "OEBPS/vector/fig.svg", svg().getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/audio/note.mp3", new byte[]{0x55, 0x66, 0x77});
        }
        return epub;
    }

    private void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private String containerXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles>"
                + "</container>";
    }

    private String opf() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"bookid\" version=\"3.0\" xml:lang=\"zh\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:identifier id=\"bookid\">urn:uuid:1234</dc:identifier>"
                + "<dc:title>古籍样书</dc:title>"
                + "<dc:language>zh-CN</dc:language>"
                + "<meta property=\"dcterms:modified\">2026-06-09T00:00:00Z</meta>"
                + "</metadata>"
                + "<manifest>"
                + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                + "<item id=\"chap1\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"css\" href=\"styles/main.css\" media-type=\"text/css\"/>"
                + "<item id=\"font1\" href=\"fonts/test.otf\" media-type=\"application/vnd.ms-opentype\"/>"
                + "<item id=\"img1\" href=\"images/pic.png\" media-type=\"image/png\"/>"
                + "<item id=\"svg1\" href=\"vector/fig.svg\" media-type=\"image/svg+xml\"/>"
                + "<item id=\"aud1\" href=\"audio/note.mp3\" media-type=\"audio/mpeg\"/>"
                + "</manifest>"
                + "<spine toc=\"ncx\">"
                + "<itemref idref=\"chap1\"/>"
                + "</spine>"
                + "</package>";
    }

    private String navXhtml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">"
                + "<head><title>目录</title></head>"
                + "<body>"
                + "<nav epub:type=\"toc\">"
                + "<ol><li><a href=\"chapter1.xhtml#c1\">第一章</a></li></ol>"
                + "</nav>"
                + "</body>"
                + "</html>";
    }

    private String tocNcx() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
                + "<head/>"
                + "<docTitle><text>古籍样书</text></docTitle>"
                + "<navMap>"
                + "<navPoint id=\"navPoint-1\" playOrder=\"1\">"
                + "<navLabel><text>第一章</text></navLabel>"
                + "<content src=\"chapter1.xhtml#c1\"/>"
                + "</navPoint>"
                + "</navMap>"
                + "</ncx>";
    }

    private String chapterXhtml() {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        body.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">");
        body.append("<head>");
        body.append("<title>第一章</title>");
        body.append("<link rel=\"stylesheet\" href=\"styles/main.css\"/>");
        body.append("</head>");
        body.append("<body id=\"c1\">");
        body.append("<p>");
        body.append("天地玄黃宇宙洪荒");
        body.append("<ruby>漢<rt>kan</rt></ruby>");
        body.append("2026");
        body.append("<img src=\"images/pic.png\" alt=\"图\" height=\"64\"/>");
        body.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\"><image href=\"vector/fig.svg\" width=\"120\" height=\"40\"/></svg>");
        body.append("<audio src=\"audio/note.mp3\"></audio>");
        for (int i = 0; i < 80; i++) {
            body.append("天地玄黃宇宙洪荒");
        }
        body.append("</p>");
        body.append("<p>");
        for (int i = 0; i < 60; i++) {
            body.append("日月盈昃辰宿列張");
        }
        body.append("</p>");
        body.append("</body>");
        body.append("</html>");
        return body.toString();
    }

    private String css() {
        return "@font-face { font-family: 'TestFont'; src: url('../fonts/test.otf'); }"
                + "body { background-image: url('../images/pic.png'); }";
    }

    private String svg() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 40 40\">"
                + "<rect x=\"0\" y=\"0\" width=\"40\" height=\"40\" fill=\"black\"/>"
                + "</svg>";
    }

    private void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}

