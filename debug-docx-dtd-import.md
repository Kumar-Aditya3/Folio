# DOCX import DTD error debugging

Status: [OPEN]
Session: docx-dtd-import

## Symptom
Importing a DOCX on Android reports: "DTD and external entities are not allowed". Desktop tests pass after allowing harmless internal DOCTYPE declarations.

## Hypotheses
1. Android SAXParserFactory rejects one of the requested XML security features.
2. The DOCX XML contains a declaration rejected by rejectDangerousXml.
3. The relationships XML, rather than document.xml, triggers rejection.
4. A non-security parser exception containing "entity" or "external" is misclassified.

## Evidence
Pre-fix runtime log:
- Line 1: document.xml has no DOCTYPE, ENTITY, or external DOCTYPE.
- Line 2: relationships XML has no DOCTYPE, ENTITY, or external DOCTYPE.
- Line 3: Android uses org.apache.harmony.xml.parsers.SAXParserFactoryImpl.
- Line 4: that provider throws SAXNotRecognizedException for http://apache.org/xml/features/disallow-doctype-decl.

Conclusion: Hypothesis 1 confirmed. Hypotheses 2 and 3 rejected. Hypothesis 4 confirmed: feature-configuration failure was misclassified by broad message matching.

## Changes
Runtime instrumentation added; minimal parser compatibility fix pending.
