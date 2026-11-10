async function loadDocument() {
    const heading = document.getElementById("document-name");
    const content = document.getElementById("document-text");
    const status = document.getElementById("reader-status");
    const download = document.getElementById("download-original");
    const raw = document.getElementById("view-raw");
    function showStatus(message, state) {
        status.textContent = message;
        status.setAttribute("data-state", state);
    }
    showStatus("Loading document...", "loading");
    try {
        const parameters = new URLSearchParams(window.location.search);
        const names = parameters.getAll("name");
        const name = names[0];
        if (names.length !== 1 || !name || !name.trim() || name === "." || name === ".."
                || [...name].some(c => c === "/" || c === "\\" || c === ":"
                    || c.charCodeAt(0) < 32 || (c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159))) {
            showStatus("Invalid document name.", "error");
            return;
        }
        heading.textContent = name;
        const identifier = new URLSearchParams({ name });
        raw.setAttribute("href", "/document/view?" + identifier.toString());
        download.setAttribute("href", "/document/download?" + identifier.toString());
        raw.hidden = false;
        download.hidden = false;
        const response = await fetch(raw.getAttribute("href"));
        if (!response.ok) {
            showStatus(response.status === 404 ? "Document not found." : "Document unavailable.", "error");
            return;
        }
        const text = await response.text();
        // Duplicate/invalid highlighting parameters still allow a plain-text document to be read.
        const query = parameters.getAll("q").length === 1 ? parameters.get("q") : "";
        const mode = parameters.getAll("mode").length === 1 ? parameters.get("mode") : "";
        const matches = HighlightPresentation.ranges(text, query, mode);
        HighlightPresentation.render(content, text, matches);
        showStatus(text.length === 0 ? "Empty document."
            : matches.length ? "Matching tokens highlighted." : "No verified highlights; showing original text.",
            text.length === 0 ? "empty" : "ready");
    } catch (error) {
        content.replaceChildren();
        showStatus("Document unavailable.", "error");
    } finally {
        content.setAttribute("aria-busy", "false");
    }
}

loadDocument();
