package com.myreading.epub.parse;

import java.io.IOException;
import java.io.InputStream;

interface EpubArchive {
    boolean exists(String entryPath) throws IOException;

    InputStream open(String entryPath) throws IOException;

    String describe();
}

