package com.mapploy.backend.sync;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PersonioFeedParser {

    private static final Map<String, double[]> LOCATIONS = locationCoordinates();

    public List<ParsedJob> parse(String xml, FeedCatalog.Feed feed) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            List<ParsedJob> jobs = new ArrayList<>();
            NodeList positions = document.getElementsByTagName("position");
            for (int i = 0; i < positions.getLength(); i++) {
                Element position = (Element) positions.item(i);
                String externalId = directText(position, "id");
                if (externalId.isBlank()) continue;

                List<String> sections = new ArrayList<>();
                NodeList descriptions = position.getElementsByTagName("jobDescription");
                for (int sectionIndex = 0; sectionIndex < descriptions.getLength(); sectionIndex++) {
                    Element section = (Element) descriptions.item(sectionIndex);
                    String name = directText(section, "name");
                    String value = directText(section, "value");
                    if (!value.isBlank()) sections.add("[" + (name.isBlank() ? "Section" : name) + "]\n" + value);
                }

                String rawDescription = String.join("\n\n", sections);
                String location = defaultString(directText(position, "office"), "Location not specified");
                double[] coordinates = coordinatesFor(location, externalId);
                String title = defaultString(directText(position, "name"), "Untitled vacancy");
                String company = defaultString(directText(position, "subcompany"), feed.name());
                String seniority = directText(position, "seniority");
                String employmentType = directText(position, "employmentType");

                jobs.add(new ParsedJob(
                        feed.key() + ":" + externalId,
                        feed.key(),
                        feed.name(),
                        "Official Personio employer feed",
                        feed.url(),
                        feed.jobBaseUrl() + externalId,
                        externalId,
                        title,
                        company,
                        location,
                        categoryFor(title, directText(position, "department")),
                        coordinates == null ? null : coordinates[0],
                        coordinates == null ? null : coordinates[1],
                        directText(position, "department"),
                        employmentType,
                        seniority,
                        directText(position, "schedule"),
                        emptyToNull(directText(position, "createdAt")),
                        rawDescription,
                        sha256(String.join("\n", title, company, location, seniority, employmentType, rawDescription))));
            }
            return jobs;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid Personio XML: " + error.getMessage(), error);
        }
    }

    private static String directText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tagName)) {
                return stripHtml(node.getTextContent());
            }
        }
        return "";
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        String text = HtmlUtils.htmlUnescape(value)
                .replaceAll("(?i)<\\s*br\\s*/?>", "\n")
                .replaceAll("(?i)<\\s*/\\s*(?:p|li|ul|ol|h\\d)\\s*>", "\n")
                .replaceAll("(?i)<\\s*li[^>]*>", "• ")
                .replaceAll("<[^>]+>", " ")
                .replace('\u00a0', ' ')
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" +([.,;:!?])", "$1")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return HtmlUtils.htmlUnescape(text);
    }

    private static double[] coordinatesFor(String location, String id) {
        String normalized = location.toLowerCase(Locale.GERMAN);
        for (Map.Entry<String, double[]> entry : LOCATIONS.entrySet()) {
            if (!normalized.contains(entry.getKey())) continue;
            int seed = id.chars().sum();
            double angle = ((seed % 12) / 12.0) * Math.PI * 2;
            double radius = 0.004 + (seed % 4) * 0.0015;
            return new double[]{
                    entry.getValue()[0] + Math.cos(angle) * radius,
                    entry.getValue()[1] + Math.sin(angle) * radius
            };
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    static String categoryFor(String title, String department) {
        String normalizedTitle = defaultString(title, "").toLowerCase(Locale.GERMAN);
        String role = (normalizedTitle + " " + defaultString(department, "")).toLowerCase(Locale.GERMAN);
        if (role.matches(".*(?:human resources|people|personal|recruiting|talent).*")) return "People & HR";
        if (role.matches(".*(?:sales|vertrieb|account|customer success|pre-sales|marketing).*")) return "Sales & Customer";
        if (role.matches(".*(?:finance|buchhalt|steuer|kaufmänn|sachbearb).*")) return "Finance & Administration";
        if (normalizedTitle.matches(".*\\bit[- ]?projekt.*")) return "IT & Software";
        if (role.matches(".*(?:project|projekt|product|produkt|scrum|continuity|pmo).*")) return "Project & Product";
        if (role.matches(".*(?:software|developer|entwick|\\bit\\b|devops|cloud|data|frontend|backend|full.?stack|test|systemadmin|informatik|architect|architekt|digital|cyber|security|erp|sap|\\bki\\b|\\bai\\b|microsoft 365|azure).*")) {
            return "IT & Software";
        }
        if (role.matches(".*(?:engineer|engineering|techniker|technik|konstruktion|mechanik|mechatronik|plc|automation|service).*")) {
            return "Engineering & Technical";
        }
        if (role.matches(".*(?:operations|logistik|lager|material|ersatzteil).*")) return "Operations & Logistics";
        return "Other roles";
    }

    private static Map<String, double[]> locationCoordinates() {
        Map<String, double[]> locations = new LinkedHashMap<>();
        locations.put("bremen", new double[]{8.8017, 53.0793});
        locations.put("hamburg", new double[]{9.9937, 53.5511});
        locations.put("berlin", new double[]{13.405, 52.52});
        locations.put("erfurt", new double[]{11.0299, 50.9848});
        locations.put("kempten", new double[]{10.316, 47.726});
        locations.put("köln", new double[]{6.9603, 50.9375});
        locations.put("cologne", new double[]{6.9603, 50.9375});
        return locations;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
