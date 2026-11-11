# Accessibility Statement

MQTT Gateway is committed to ensuring digital accessibility for all users, administrators, and contributors, including people with disabilities. We continuously evaluate and improve the user experience for everyone and apply relevant accessibility standards.

## Standards & Conformance Target

We aim to adhere to the **[Web Content Accessibility Guidelines (WCAG) 2.2 Level AA](https://www.w3.org/TR/WCAG22/)** across all interactive web surfaces provided by the project:

- The **Admin Dashboard** (`/gateway/`): route management, health probe monitoring, incident tracking, and audit event logs.
- The **Web Documentation** (`/gateway/docs`): interactive guides, prompt copying features, and reference docs.

In addition, we design command-line tools and console outputs (`scripts/smoke.py`, Docker logs, and Maven targets) to be clean, parseable, and friendly to terminal screen readers.

## Accessibility Features Implemented

- **Semantic HTML & Landmarks:** Web interfaces utilize proper HTML5 elements (`<main>`, `<nav>`, `<header>`, `<section>`, `<table>`, `<form>`) and ARIA attributes to support screen reader navigation.
- **Keyboard Operability:** All critical administrative workflows—including route creation, modal dialogues, pagination, and filter controls—are reachable and operable using standard keyboard navigation (`Tab`, `Shift+Tab`, `Enter`, `Escape`, `Space`).
- **Focus Visibility:** Focus indicators are preserved across interactive buttons, form inputs, and links so keyboard users can track their position.
- **Color Contrast:** Text, buttons, badges (such as health probe statuses `allow`, `deny`, `ignore`), and chart legends are chosen to meet or exceed contrast ratios of 4.5:1 for normal text and 3:1 for large text or graphical UI components. Status is never communicated by color alone; text labels and distinct icons or shapes are provided alongside colors.
- **Responsive Layout & Zoom:** The administrative UI supports responsive scaling, working on both desktop and mobile viewports, and reflows gracefully up to 200% browser zoom without breaking usability.
- **Alternative Text Formats:** Documentation at `/gateway/docs` supports content negotiation (`Accept: text/markdown` and `/gateway/docs.md`) as well as dedicated files ([`docs/llms.txt`](docs/llms.txt)), allowing screen readers and automated tools to ingest clean, distraction-free markdown text.

## Known Limitations & Planned Enhancements

While we strive to ensure broad accessibility, we recognize areas currently being refined:

- **Data Visualizations & Latency Charts:** Real-time latency sparklines and canvas charts provide summary data, but we are actively improving accompanying accessible tabular data equivalents for screen readers.
- **Complex Audit Log Filters:** Advanced multi-criteria date and time picker filtering in the audit view may require multiple tab stops; improvements to keyboard shortcuts are ongoing.

## Feedback & Reporting Accessibility Barriers

We welcome your feedback on the accessibility of MQTT Gateway. If you encounter accessibility barriers, difficult-to-navigate UI components, or unreadable outputs:

1. **Open an Issue:** Submit an issue on [GitHub Issues](https://github.com/kgvicente/mqtt-gateway/issues) using the `accessibility` label.
2. **Include Details:**
   - The page, URL, or CLI command where the barrier occurred.
   - The assistive technology (e.g. NVDA, JAWS, VoiceOver, screen magnifier) and browser/OS you used.
   - A brief description of the expected behavior versus what occurred.
3. **Direct Contact:** You can also contact the maintainer [@kgvicente](https://github.com/kgvicente) directly.

We treat accessibility issues as standard bugs and prioritize fixes in upcoming release cycles.
