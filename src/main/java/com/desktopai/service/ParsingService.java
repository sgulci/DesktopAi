package com.desktopai.service;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for parsing documents using Apache Tika.
 * Supports PDF, DOCX, TXT, MD, HTML, RTF, ODT, PPT, XLS and more.
 */
public class ParsingService {
    private final Tika tika;
    private final AutoDetectParser parser;

    public ParsingService() {
        this.tika = new Tika();
        this.parser = new AutoDetectParser();
    }

    /**
     * Parses a document and returns its text content.
     */
    public ParseResult parse(File file) throws IOException, TikaException {
        try (InputStream stream = new FileInputStream(file)) {
            return parse(stream, file.getName());
        }
    }

    /**
     * Parses a document from an input stream.
     */
    public ParseResult parse(InputStream stream, String filename) throws IOException, TikaException {
        // Use unlimited content length (-1)
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            parser.parse(stream, handler, metadata, context);
        } catch (org.xml.sax.SAXException e) {
            throw new TikaException("Failed to parse document", e);
        }

        String content = handler.toString();
        String contentType = metadata.get(Metadata.CONTENT_TYPE);

        // Extract additional metadata
        Map<String, String> metadataMap = new HashMap<>();
        for (String name : metadata.names()) {
            metadataMap.put(name, metadata.get(name));
        }

        return new ParseResult(content, contentType, metadataMap);
    }

    /**
     * Detects the content type of a file.
     */
    public String detectContentType(File file) throws IOException {
        return tika.detect(file);
    }

    /**
     * Result of parsing a document.
     */
    public static class ParseResult {
        private final String content;
        private final String contentType;
        private final Map<String, String> metadata;

        public ParseResult(String content, String contentType, Map<String, String> metadata) {
            this.content = content;
            this.contentType = contentType;
            this.metadata = metadata;
        }

        public String getContent() {
            return content;
        }

        public String getContentType() {
            return contentType;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }
    }
}
