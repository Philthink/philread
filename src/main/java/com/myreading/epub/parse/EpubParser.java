package com.myreading.epub.parse;

import com.myreading.epub.model.Book;
import com.myreading.epub.model.Chapter;
import com.myreading.epub.model.Manifest;
import com.myreading.epub.model.ManifestItem;
import com.myreading.epub.model.Metadata;
import com.myreading.epub.model.Navigation;
import com.myreading.epub.model.NavigationNode;
import com.myreading.epub.model.ParseIssue;
import com.myreading.epub.model.ParseIssue.Severity;
import com.myreading.epub.model.Resource;
import com.myreading.epub.model.ResourceType;
import com.myreading.epub.model.Spine;
import com.myreading.epub.model.SpineItem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EpubParser {
    public Book parse(Path epubPath) throws IOException {
        EpubArchive archive = new ZipEpubArchive(epubPath);
        List<ParseIssue> issues = new ArrayList<>();

        String rootfilePath = readRootfilePath(archive);
        String opfXml = readText(archive, rootfilePath);
        Document opf = SafeXml.parse(opfXml);
        String opfBasePath = PathUtil.parent(rootfilePath);

        Metadata metadata = parseMetadata(opf);
        Manifest manifest = parseManifest(opf, opfBasePath, archive);
        Spine spine = parseSpine(opf, manifest);

        ManifestItem navItem = findNavItem(manifest);
        ManifestItem ncxItem = findNcxItem(manifest);

        Navigation navigation = parseNavigation(archive, navItem, ncxItem, manifest, issues);

        Map<String, Resource> resourcesByHref = buildResources(archive, manifest, opfBasePath);
        Map<String, String> chapterTitles = buildNavigationTitleIndex(navigation);
        List<Chapter> chapters = buildChapters(archive, spine, manifest, opfBasePath, chapterTitles, issues);

        return new Book(archive.describe(), metadata, manifest, spine, navigation, chapters, resourcesByHref, issues);
    }

    private String readRootfilePath(EpubArchive archive) throws IOException {
        if (!archive.exists("META-INF/container.xml")) {
            throw new IOException("Missing META-INF/container.xml");
        }
        Document container = SafeXml.parse(archive.open("META-INF/container.xml"));
        NodeList rootfiles = container.getElementsByTagNameNS("*", "rootfile");
        if (rootfiles.getLength() == 0) {
            throw new IOException("container.xml does not declare a rootfile");
        }
        Element rootfile = (Element) rootfiles.item(0);
        String fullPath = rootfile.getAttribute("full-path");
        if (fullPath == null || fullPath.isEmpty()) {
            throw new IOException("container.xml rootfile missing full-path");
        }
        return PathUtil.normalize(fullPath);
    }

    private String readText(EpubArchive archive, String entryPath) throws IOException {
        InputStream input = null;
        try {
            input = archive.open(entryPath);
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private Metadata parseMetadata(Document opf) {
        NodeList metadataNodes = opf.getElementsByTagNameNS("*", "metadata");
        if (metadataNodes.getLength() == 0) {
            return new Metadata(Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    null,
                    Collections.<String, List<String>>emptyMap());
        }
        Element metadata = (Element) metadataNodes.item(0);
        List<String> titles = textsByName(metadata, "title");
        List<String> creators = textsByName(metadata, "creator");
        List<String> languages = textsByName(metadata, "language");
        List<String> identifiers = textsByName(metadata, "identifier");
        List<String> publishers = textsByName(metadata, "publisher");
        List<String> descriptions = textsByName(metadata, "description");
        List<String> subjects = textsByName(metadata, "subject");
        List<String> dates = textsByName(metadata, "date");
        String modified = null;
        Map<String, List<String>> extras = new LinkedHashMap<>();

        NodeList children = metadata.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            String localName = XmlUtils.localName(element);
            if ("meta".equals(localName)) {
                String property = element.getAttribute("property");
                String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
                if ("dcterms:modified".equals(property)) {
                    modified = value;
                } else if (property != null && !property.isEmpty()) {
                    extras.computeIfAbsent(property, k -> new ArrayList<>()).add(value);
                }
            } else if (!Arrays.asList("title", "creator", "language", "identifier", "publisher", "description", "subject", "date").contains(localName)) {
                String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
                extras.computeIfAbsent(localName, k -> new ArrayList<>()).add(value);
            }
        }

        return new Metadata(titles, creators, languages, identifiers, publishers, descriptions, subjects, dates, modified, extras);
    }

    private List<String> textsByName(Element parent, String localName) {
        List<String> result = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null) {
                result.add(text.trim());
            }
        }
        return result;
    }

    private Manifest parseManifest(Document opf, String opfBasePath, EpubArchive archive) {
        NodeList manifestNodes = opf.getElementsByTagNameNS("*", "manifest");
        if (manifestNodes.getLength() == 0) {
            return new Manifest(Collections.<ManifestItem>emptyList());
        }
        Element manifestElement = (Element) manifestNodes.item(0);
        NodeList items = manifestElement.getElementsByTagNameNS("*", "item");
        List<ManifestItem> manifestItems = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String href = PathUtil.resolve(opfBasePath, item.getAttribute("href"));
            String mediaType = item.getAttribute("media-type");
            Set<String> properties = splitTokens(item.getAttribute("properties"));
            String fallback = item.getAttribute("fallback");
            String mediaOverlay = item.getAttribute("media-overlay");
            manifestItems.add(new ManifestItem(id, href, mediaType, properties, fallback, mediaOverlay));
        }
        return new Manifest(manifestItems);
    }

    private Spine parseSpine(Document opf, Manifest manifest) {
        NodeList spineNodes = opf.getElementsByTagNameNS("*", "spine");
        if (spineNodes.getLength() == 0) {
            return new Spine(Collections.<SpineItem>emptyList());
        }
        Element spineElement = (Element) spineNodes.item(0);
        NodeList itemrefs = spineElement.getElementsByTagNameNS("*", "itemref");
        List<SpineItem> spineItems = new ArrayList<>();
        for (int i = 0; i < itemrefs.getLength(); i++) {
            Element itemref = (Element) itemrefs.item(i);
            String idref = itemref.getAttribute("idref");
            ManifestItem manifestItem = manifest.getById(idref);
            if (manifestItem == null) {
                continue;
            }
            boolean linear = !"no".equalsIgnoreCase(itemref.getAttribute("linear"));
            Set<String> properties = splitTokens(itemref.getAttribute("properties"));
            spineItems.add(new SpineItem(idref, manifestItem.getHref(), linear, properties));
        }
        return new Spine(spineItems);
    }

    private ManifestItem findNavItem(Manifest manifest) {
        for (ManifestItem item : manifest.getItems()) {
            if (item.getProperties().contains("nav")) {
                return item;
            }
        }
        return null;
    }

    private ManifestItem findNcxItem(Manifest manifest) {
        for (ManifestItem item : manifest.getItems()) {
            if ("application/x-dtbncx+xml".equalsIgnoreCase(item.getMediaType())) {
                return item;
            }
        }
        return null;
    }

    private Navigation parseNavigation(EpubArchive archive,
                                       ManifestItem navItem,
                                       ManifestItem ncxItem,
                                       Manifest manifest,
                                       List<ParseIssue> issues) throws IOException {
        if (navItem != null) {
            try {
                Document nav = SafeXml.parse(readText(archive, navItem.getHref()));
                List<NavigationNode> roots = parseNavXhtml(nav, navItem.getHref());
                if (!roots.isEmpty()) {
                    return new Navigation(navItem.getHref(), roots);
                }
            } catch (IOException e) {
                issues.add(new ParseIssue(Severity.WARNING, e.getMessage(), navItem.getHref()));
            }
        }
        if (ncxItem != null) {
            try {
                Document ncx = SafeXml.parse(readText(archive, ncxItem.getHref()));
                List<NavigationNode> roots = parseNcx(ncx, ncxItem.getHref());
                return new Navigation(ncxItem.getHref(), roots);
            } catch (IOException e) {
                issues.add(new ParseIssue(Severity.WARNING, e.getMessage(), ncxItem.getHref()));
            }
        }
        return new Navigation(null, Collections.<NavigationNode>emptyList());
    }

    private List<NavigationNode> parseNavXhtml(Document navDocument, String navHref) {
        List<NavigationNode> roots = new ArrayList<>();
        NodeList navNodes = navDocument.getElementsByTagNameNS("*", "nav");
        for (int i = 0; i < navNodes.getLength(); i++) {
            Element nav = (Element) navNodes.item(i);
            String epubType = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type");
            String role = nav.getAttribute("role");
            if (!"toc".equalsIgnoreCase(epubType) && !"doc-toc".equalsIgnoreCase(role)) {
                continue;
            }
            NodeList orderedLists = nav.getElementsByTagNameNS("*", "ol");
            for (int j = 0; j < orderedLists.getLength(); j++) {
                roots.addAll(parseNavList((Element) orderedLists.item(j), navHref));
            }
        }
        return roots;
    }

    private List<NavigationNode> parseNavList(Element listElement, String navHref) {
        List<NavigationNode> result = new ArrayList<>();
        NodeList items = listElement.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"li".equalsIgnoreCase(XmlUtils.localName(node))) {
                continue;
            }
            result.add(parseNavItem((Element) node, navHref));
        }
        return result;
    }

    private NavigationNode parseNavItem(Element li, String navHref) {
        String title = "";
        String href = null;
        List<NavigationNode> children = new ArrayList<>();
        NodeList nodes = li.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String localName = XmlUtils.localName(node);
            if ("a".equalsIgnoreCase(localName)) {
                Element anchor = (Element) node;
                title = anchor.getTextContent() == null ? "" : anchor.getTextContent().trim();
                href = PathUtil.resolveWithFragment(navHref, anchor.getAttribute("href"));
            } else if ("ol".equalsIgnoreCase(localName)) {
                children.addAll(parseNavList((Element) node, navHref));
            }
        }
        return new NavigationNode(title, href, children);
    }

    private List<NavigationNode> parseNcx(Document ncxDocument, String ncxHref) {
        List<NavigationNode> roots = new ArrayList<>();
        NodeList navMaps = ncxDocument.getElementsByTagNameNS("*", "navMap");
        if (navMaps.getLength() == 0) {
            return roots;
        }
        Element navMap = (Element) navMaps.item(0);
        NodeList navPoints = navMap.getChildNodes();
        for (int i = 0; i < navPoints.getLength(); i++) {
            Node node = navPoints.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"navPoint".equalsIgnoreCase(XmlUtils.localName(node))) {
                continue;
            }
            roots.add(parseNcxNavPoint((Element) node, ncxHref));
        }
        return roots;
    }

    private NavigationNode parseNcxNavPoint(Element navPoint, String ncxHref) {
        String title = "";
        String href = null;
        List<NavigationNode> children = new ArrayList<>();
        NodeList nodes = navPoint.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String localName = XmlUtils.localName(node);
            if ("navLabel".equalsIgnoreCase(localName)) {
                NodeList labels = ((Element) node).getElementsByTagNameNS("*", "text");
                if (labels.getLength() > 0) {
                    title = labels.item(0).getTextContent().trim();
                }
            } else if ("content".equalsIgnoreCase(localName)) {
                href = PathUtil.resolveWithFragment(ncxHref, ((Element) node).getAttribute("src"));
            } else if ("navPoint".equalsIgnoreCase(localName)) {
                children.add(parseNcxNavPoint((Element) node, ncxHref));
            }
        }
        return new NavigationNode(title, href, children);
    }

    private Map<String, String> buildNavigationTitleIndex(Navigation navigation) {
        Map<String, String> titlesByHref = new LinkedHashMap<>();
        collectNavigationTitles(navigation.getRoots(), titlesByHref);
        return titlesByHref;
    }

    private void collectNavigationTitles(List<NavigationNode> nodes, Map<String, String> titlesByHref) {
        for (NavigationNode node : nodes) {
            if (node.getHref() != null && !node.getHref().isEmpty()) {
                String normalizedHref = PathUtil.stripFragmentAndQuery(node.getHref());
                if (!titlesByHref.containsKey(normalizedHref)) {
                    titlesByHref.put(normalizedHref, node.getTitle());
                }
            }
            if (!node.getChildren().isEmpty()) {
                collectNavigationTitles(node.getChildren(), titlesByHref);
            }
        }
    }

    private Map<String, Resource> buildResources(EpubArchive archive, Manifest manifest, String opfBasePath) {
        Map<String, Resource> resources = new LinkedHashMap<>();
        for (ManifestItem item : manifest.getItems()) {
            String type = item.getMediaType();
            ResourceType resourceType = classify(type, item.getHref());
            resources.put(item.getHref(), new Resource(
                    item.getId(),
                    item.getHref(),
                    item.getMediaType(),
                    resourceType,
                    item.getProperties(),
                    item.getFallback(),
                    () -> archive.open(item.getHref())));
        }
        return resources;
    }

    private ResourceType classify(String mediaType, String href) {
        if (mediaType == null) {
            return ResourceType.OTHER;
        }
        String normalized = mediaType.toLowerCase();
        if (normalized.contains("xhtml") || normalized.contains("html")) {
            return ResourceType.XHTML;
        }
        if (normalized.contains("css")) {
            return ResourceType.CSS;
        }
        if (normalized.contains("ncx") || normalized.contains("xml")) {
            return ResourceType.XML;
        }
        if (normalized.startsWith("image/svg")) {
            return ResourceType.SVG;
        }
        if (normalized.startsWith("image/")) {
            return ResourceType.IMAGE;
        }
        if (normalized.startsWith("audio/")) {
            return ResourceType.AUDIO;
        }
        if (normalized.contains("font") || normalized.contains("opentype") || normalized.contains("truetype") || normalized.contains("woff")) {
            return ResourceType.FONT;
        }
        if (normalized.startsWith("text/")) {
            return ResourceType.TEXT;
        }
        if (href != null && href.toLowerCase().endsWith(".svg")) {
            return ResourceType.SVG;
        }
        return ResourceType.OTHER;
    }

    private List<Chapter> buildChapters(EpubArchive archive,
                                        Spine spine,
                                        Manifest manifest,
                                        String opfBasePath,
                                        Map<String, String> chapterTitles,
                                        List<ParseIssue> issues) throws IOException {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < spine.getItems().size(); i++) {
            SpineItem spineItem = spine.getItems().get(i);
            ManifestItem manifestItem = manifest.getById(spineItem.getIdref());
            if (manifestItem == null) {
                continue;
            }
            String content = readText(archive, manifestItem.getHref());
            String title = chapterTitles.get(manifestItem.getHref());
            if (title == null || title.isEmpty()) {
                title = extractChapterTitle(content, manifestItem.getHref());
            }
            Set<String> references = new LinkedHashSet<>();
            references.add(manifestItem.getHref());
            references.addAll(scanChapterResources(content, manifestItem.getHref(), opfBasePath, manifest, archive));
            chapters.add(new Chapter(
                    spineItem.getIdref(),
                    manifestItem.getHref(),
                    title,
                    i,
                    spineItem.isLinear(),
                    content,
                    references));
        }
        return chapters;
    }

    private String extractChapterTitle(String content, String href) {
        try {
            Document document = SafeXml.parse(content);
            NodeList titles = document.getElementsByTagNameNS("*", "title");
            if (titles.getLength() > 0) {
                String text = titles.item(0).getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
            for (String heading : Arrays.asList("h1", "h2", "h3", "h4", "h5", "h6")) {
                NodeList nodes = document.getElementsByTagNameNS("*", heading);
                if (nodes.getLength() > 0) {
                    String text = nodes.item(0).getTextContent();
                    if (text != null && !text.trim().isEmpty()) {
                        return text.trim();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return href;
    }

    private Set<String> scanChapterResources(String content,
                                             String href,
                                             String opfBasePath,
                                             Manifest manifest,
                                             EpubArchive archive) {
        Set<String> references = new LinkedHashSet<>();
        try {
            Document document = SafeXml.parse(content);
            collectXhtmlResourceRefs(document, href, references);
            collectLinkedCssRefs(document, href, references, archive, manifest);
        } catch (IOException ignored) {
        }
        return references;
    }

    private void collectLinkedCssRefs(Document document,
                                      String chapterHref,
                                      Set<String> references,
                                      EpubArchive archive,
                                      Manifest manifest) throws IOException {
        NodeList links = document.getElementsByTagNameNS("*", "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String rel = link.getAttribute("rel");
            if (rel == null || !rel.toLowerCase().contains("stylesheet")) {
                continue;
            }
            String href = PathUtil.resolve(chapterHref, link.getAttribute("href"));
            references.add(href);
            ManifestItem item = manifest.getByHref(href);
            if (item != null && item.getMediaType() != null && item.getMediaType().toLowerCase().contains("css")) {
                try {
                    String css = readText(archive, href);
                    references.addAll(CssResourceScanner.scan(css, href));
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void collectXhtmlResourceRefs(Document document, String chapterHref, Set<String> references) {
        collectElementRefs(document.getDocumentElement(), chapterHref, references);
    }

    private void collectElementRefs(Element element, String baseHref, Set<String> references) {
        if (element == null) {
            return;
        }
        String localName = XmlUtils.localName(element).toLowerCase();
        if ("img".equals(localName) || "image".equals(localName) || "audio".equals(localName) || "source".equals(localName) || "video".equals(localName) || "object".equals(localName) || "embed".equals(localName) || "script".equals(localName) || "link".equals(localName)) {
            for (String attr : Arrays.asList("src", "href", "data", "poster")) {
                if (element.hasAttribute(attr)) {
                    String value = element.getAttribute(attr);
                    if (value != null && !value.isEmpty()) {
                        references.add(PathUtil.resolve(baseHref, value));
                    }
                }
            }
            String xlinkHref = element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            if (xlinkHref != null && !xlinkHref.isEmpty()) {
                references.add(PathUtil.resolve(baseHref, xlinkHref));
            }
        }
        if (element.hasAttribute("style")) {
            references.addAll(CssResourceScanner.scan(element.getAttribute("style"), baseHref));
        }
        if ("style".equals(localName)) {
            references.addAll(CssResourceScanner.scan(element.getTextContent(), baseHref));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                collectElementRefs((Element) node, baseHref, references);
            }
        }
    }

    private Set<String> splitTokens(String raw) {
        Set<String> tokens = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return tokens;
        }
        for (String token : raw.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
