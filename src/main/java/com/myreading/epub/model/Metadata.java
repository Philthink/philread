package com.myreading.epub.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Metadata {
    private final List<String> titles;
    private final List<String> creators;
    private final List<String> languages;
    private final List<String> identifiers;
    private final List<String> publishers;
    private final List<String> descriptions;
    private final List<String> subjects;
    private final List<String> dates;
    private final String modified;
    private final Map<String, List<String>> extras;

    public Metadata(List<String> titles,
                    List<String> creators,
                    List<String> languages,
                    List<String> identifiers,
                    List<String> publishers,
                    List<String> descriptions,
                    List<String> subjects,
                    List<String> dates,
                    String modified,
                    Map<String, List<String>> extras) {
        this.titles = unmodifiableCopy(titles);
        this.creators = unmodifiableCopy(creators);
        this.languages = unmodifiableCopy(languages);
        this.identifiers = unmodifiableCopy(identifiers);
        this.publishers = unmodifiableCopy(publishers);
        this.descriptions = unmodifiableCopy(descriptions);
        this.subjects = unmodifiableCopy(subjects);
        this.dates = unmodifiableCopy(dates);
        this.modified = modified;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    private static List<String> unmodifiableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public List<String> getTitles() {
        return titles;
    }

    public List<String> getCreators() {
        return creators;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public List<String> getIdentifiers() {
        return identifiers;
    }

    public List<String> getPublishers() {
        return publishers;
    }

    public List<String> getDescriptions() {
        return descriptions;
    }

    public List<String> getSubjects() {
        return subjects;
    }

    public List<String> getDates() {
        return dates;
    }

    public String getModified() {
        return modified;
    }

    public Map<String, List<String>> getExtras() {
        return extras;
    }

    public String getTitle() {
        return titles.isEmpty() ? null : titles.get(0);
    }

    public String getCreator() {
        return creators.isEmpty() ? null : creators.get(0);
    }

    public String getLanguage() {
        return languages.isEmpty() ? null : languages.get(0);
    }

    public String getIdentifier() {
        return identifiers.isEmpty() ? null : identifiers.get(0);
    }
}

