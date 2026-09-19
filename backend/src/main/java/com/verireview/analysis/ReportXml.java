package com.verireview.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Hardenend, namespace-unaware DOM helpers for tool XML reports. */
final class ReportXml {

  private static final Logger log = LoggerFactory.getLogger(ReportXml.class);

  private ReportXml() {
  }

  /** Parses a report file; empty on missing/malformed input (never throws). */
  static Document parse(Path report) {
    if (report == null || !Files.isRegularFile(report)) {
      return null;
    }
    try (InputStream in = Files.newInputStream(report)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      // XXE defense: reports come from our own sandbox, but never parse blind.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(in);
    } catch (Exception e) {
      log.warn("Ignoring malformed tool report {}: {}", report, e.toString());
      return null;
    }
  }

  static List<Element> children(Element parent, String tag) {
    List<Element> matches = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
        matches.add((Element) node);
      }
    }
    return matches;
  }

  static List<Element> descendants(Document document, String tag) {
    List<Element> matches = new ArrayList<>();
    NodeList nodes = document.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        matches.add((Element) node);
      }
    }
    return matches;
  }

  static Integer intOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed < 0 ? null : parsed;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
