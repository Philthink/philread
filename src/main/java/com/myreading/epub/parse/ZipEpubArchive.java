package com.myreading.epub.parse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ZipEpubArchive implements EpubArchive {
    private final Path epubPath;

    ZipEpubArchive(Path epubPath) {
        this.epubPath = epubPath;
    }

    @Override
    public boolean exists(String entryPath) throws IOException {
        ZipFile zipFile = new ZipFile(epubPath.toFile());
        try {
            return zipFile.getEntry(entryPath) != null;
        } finally {
            zipFile.close();
        }
    }

    @Override
    public InputStream open(String entryPath) throws IOException {
        final ZipFile zipFile = new ZipFile(epubPath.toFile());
        ZipEntry entry = zipFile.getEntry(entryPath);
        if (entry == null) {
            zipFile.close();
            throw new IOException("Missing EPUB entry: " + entryPath);
        }
        InputStream input = zipFile.getInputStream(entry);
        return new FilterInputStream(input) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zipFile.close();
                }
            }
        };
    }

    @Override
    public String describe() {
        return epubPath.toAbsolutePath().toString();
    }
}

