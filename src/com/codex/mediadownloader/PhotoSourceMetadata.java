package com.codex.mediadownloader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Builds an ASCII XMP packet whose numeric character references preserve Unicode post text. */
final class PhotoSourceMetadata {
    private static final String RDF_END = "</rdf:RDF>";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";
    private PhotoSourceMetadata() { }

    static String xmp(String existing, String sourceUrl, String creator, PostText text) throws Exception {
        StringBuilder fields = new StringBuilder();
        fields.append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            .append("xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">");
        element(fields, "dc:source", sourceUrl);
        if (!creator.isEmpty()) {
            fields.append("<dc:creator><rdf:Seq><rdf:li>");
            xmlText(fields, creator);
            fields.append("</rdf:li></rdf:Seq></dc:creator>");
        }
        alternative(fields, "dc:title", text.title);
        alternative(fields, "dc:description", text.description);
        if (!text.hashtags.isEmpty()) {
            fields.append("<dc:subject><rdf:Bag>");
            for (String tag : text.hashtags.split(", ")) {
                if (tag.isEmpty()) continue;
                fields.append("<rdf:li>");
                xmlText(fields, tag);
                fields.append("</rdf:li>");
            }
            fields.append("</rdf:Bag></dc:subject>");
        }
        fields.append("</rdf:Description>");
        if (existing == null || existing.isEmpty())
            return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
                "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                fields + RDF_END + "</x:xmpmeta>";
        if (existing.contains("<!DOCTYPE") || existing.contains("<!ENTITY"))
            throw new IllegalArgumentException("Existing photo XMP contains a DTD or entity declaration");
        DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
        parser.setNamespaceAware(true);
        DocumentBuilder reader = parser.newDocumentBuilder();
        reader.setErrorHandler(new DefaultHandler() {
            @Override public void fatalError(SAXParseException error) throws SAXParseException {
                throw error;
            }
        });
        Document old = reader.parse(
            new ByteArrayInputStream(existing.getBytes(StandardCharsets.UTF_8)));
        for (Node child = old.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE &&
                "xpacket".equals(child.getNodeName())) old.removeChild(child);
            child = next;
        }
        NodeList containers = old.getElementsByTagNameNS(RDF_NS, "RDF");
        if (containers.getLength() != 1)
            throw new IllegalArgumentException("Existing photo XMP has no unique RDF container");
        NodeList descriptions = old.getElementsByTagNameNS(RDF_NS, "Description");
        for (int i = 0; i < descriptions.getLength(); i++) {
            Node container = descriptions.item(i);
            for (Node child = container.getFirstChild(); child != null;) {
                Node next = child.getNextSibling();
                if (DC_NS.equals(child.getNamespaceURI()) &&
                    ("source".equals(child.getLocalName()) ||
                     "creator".equals(child.getLocalName()) && !creator.isEmpty() ||
                     "title".equals(child.getLocalName()) && !text.title.isEmpty() ||
                     "description".equals(child.getLocalName()) && !text.description.isEmpty() ||
                     "subject".equals(child.getLocalName()) && !text.hashtags.isEmpty()))
                    container.removeChild(child);
                child = next;
            }
        }
        String newPacket = xmp(null, sourceUrl, creator, text);
        Document added = reader.parse(
            new ByteArrayInputStream(newPacket.getBytes(StandardCharsets.US_ASCII)));
        Node description = added.getElementsByTagNameNS(RDF_NS, "Description").item(0);
        containers.item(0).appendChild(old.importNode(description, true));
        Transformer writer = TransformerFactory.newInstance().newTransformer();
        writer.setOutputProperty(OutputKeys.ENCODING, "US-ASCII");
        writer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writer.transform(new DOMSource(old), new StreamResult(output));
        String merged = new String(output.toByteArray(), StandardCharsets.US_ASCII)
            .replace("\r", "&#xA;").replace("\n", "&#xA;").replace("\t", "&#x9;");
        if (!ascii(merged)) {
            for (int i = 0; i < merged.length(); i++)
                if (merged.charAt(i) > 126 || merged.charAt(i) < 32)
                    throw new IllegalArgumentException("Merged XMP character " +
                        Integer.toHexString(merged.charAt(i)) + " at " + i);
        }
        return merged;
    }

    static boolean ascii(String value) {
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) > 126 || value.charAt(i) < 32) return false;
        return true;
    }

    private static void element(StringBuilder xml, String tag, String value) {
        xml.append('<').append(tag).append('>');
        xmlText(xml, value);
        xml.append("</").append(tag).append('>');
    }

    private static void alternative(StringBuilder xml, String tag, String value) {
        if (value.isEmpty()) return;
        xml.append('<').append(tag).append("><rdf:Alt><rdf:li xml:lang=\"x-default\">");
        xmlText(xml, value);
        xml.append("</rdf:li></rdf:Alt></").append(tag).append('>');
    }

    private static void xmlText(StringBuilder xml, String value) {
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i);
            i += Character.charCount(point);
            if (point == '&') xml.append("&amp;");
            else if (point == '<') xml.append("&lt;");
            else if (point == '>') xml.append("&gt;");
            else if (point == '"') xml.append("&quot;");
            else if (point == '\'') xml.append("&apos;");
            else if (point >= 0xd800 && point <= 0xdfff ||
                (point & 0xffff) == 0xfffe || (point & 0xffff) == 0xffff ||
                point < 32 && point != '\n' && point != '\r' && point != '\t')
                xml.append("&#xFFFD;");
            else if (point > 127 || point == '\n' || point == '\r' || point == '\t')
                xml.append("&#x").append(Integer.toHexString(point)).append(';');
            else xml.append((char) point);
        }
    }
}
