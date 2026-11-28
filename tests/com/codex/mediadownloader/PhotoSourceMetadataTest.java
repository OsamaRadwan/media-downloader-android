package com.codex.mediadownloader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

public final class PhotoSourceMetadataTest {
    public static void main(String[] args) throws Exception {
        String source = "https://www.instagram.com/p/ABC123/";
        PostText post = PostText.from("عنوان & < > \" 😀", "测试 #标签\nمرحبا",
            Arrays.asList("标签"));
        String xmp = PhotoSourceMetadata.xmp(null, source, "مصور", post);
        check(PhotoSourceMetadata.ascii(xmp) && !xmp.contains("测试") &&
            !PhotoSourceMetadata.ascii("مصور") && !PhotoSourceMetadata.ascii("one\ntwo"),
            "XMP must use printable ASCII while keeping non-ASCII post text encoded");
        Document xml = parse(xmp);
        check(source.equals(xml.getElementsByTagName("dc:source").item(0).getTextContent()) &&
            "مصور".equals(xml.getElementsByTagName("dc:creator").item(0).getTextContent()) &&
            post.title.equals(xml.getElementsByTagName("dc:title").item(0).getTextContent()) &&
            post.description.equals(xml.getElementsByTagName("dc:description").item(0).getTextContent()) &&
            "#标签".equals(xml.getElementsByTagName("dc:subject").item(0).getTextContent()),
            "Standard XMP fields must recover the exact Unicode source details");
        String old = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:old=\"urn:old\"><old:license>Keep</old:license>" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>";
        String merged = PhotoSourceMetadata.xmp(old, source, "", PostText.from("", "", null));
        check("Keep".equals(parse(merged).getElementsByTagName("old:license").item(0).getTextContent()) &&
            merged.contains("<dc:source>") && PhotoSourceMetadata.ascii(merged),
            "A compatible existing XMP packet must retain its original fields");
        String unicodeOld = old.replace("Keep", "حقوق محفوظة");
        String unicodeMerged = PhotoSourceMetadata.xmp(unicodeOld, source, "", post);
        check("حقوق محفوظة".equals(parse(unicodeMerged).getElementsByTagName("old:license")
            .item(0).getTextContent()) && PhotoSourceMetadata.ascii(unicodeMerged),
            "Existing Unicode XMP must survive an ASCII-safe merge");
        String oldTitle = old.replace("xmlns:old=\"urn:old\"",
            "xmlns:old=\"urn:old\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
            .replace("</rdf:Description>", "<dc:title>Old title</dc:title></rdf:Description>");
        Document replaced = parse(PhotoSourceMetadata.xmp(oldTitle, source, "", post));
        check(replaced.getElementsByTagName("dc:title").getLength() == 1 &&
            post.title.equals(replaced.getElementsByTagName("dc:title").item(0).getTextContent()) &&
            "Keep".equals(replaced.getElementsByTagName("old:license").item(0).getTextContent()),
            "Post metadata must replace conflicting XMP fields without losing unrelated fields");
        try {
            PhotoSourceMetadata.xmp("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]>" + old,
                source, "", post);
            throw new AssertionError("Untrusted XMP entities must fail before altering the file");
        } catch (Exception expected) { }
        System.out.println("PhotoSourceMetadataTest passed");
    }

    private static Document parse(String xmp) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
