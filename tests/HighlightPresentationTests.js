// Dependency-free presentation/reader checks; not a substitute for Microsoft Edge testing.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const highlight = require("../web/highlight.js");
let passed = 0;
async function test(label, check) { await check(); passed++; console.log("PASS: " + label); }
function words(text, query, mode = "keyword") { return highlight.ranges(text, query, mode).map(r => text.slice(r.start, r.end)); }
function dom() {
    const document = {};
    document.createElement = tagName => ({
        tagName, ownerDocument: document, children: [], attributes: {}, hidden: true, _text: "",
        get textContent() { return this._text + this.children.map(node => node.textContent).join(""); },
        set textContent(value) { this.children = []; this._text = String(value); },
        appendChild(node) { this.children.push(node); },
        replaceChildren() { this.children = []; this._text = ""; },
        setAttribute(key, value) { this.attributes[key] = value; },
        getAttribute(key) { return this.attributes[key]; },
        set innerHTML(value) { throw new Error("Unsafe HTML: " + value); }
    });
    document.createTextNode = text => { const node = document.createElement("#text"); node.textContent = text; return node; };
    return document;
}
async function reader(parameters, text = "Java, programming.\nOriginal text.", response = { ok: true }) {
    const document = dom(); const elements = {};
    for (const id of ["document-name", "document-text", "reader-status", "download-original", "view-raw"]) { elements[id] = document.createElement("div"); }
    document.getElementById = id => elements[id]; const requests = [];
    await vm.runInNewContext(fs.readFileSync("web/reader.js", "utf8"), {
        document, URLSearchParams, HighlightPresentation: highlight, window: { location: { search: parameters } },
        async fetch(url) { requests.push(url); if (response.error) { throw new Error("offline"); } return { ...response, async text() { if (response.bodyError) { throw new Error("decode"); } return text; } }; }
    });
    return { elements, requests };
}
async function main() {
    await test("Keyword normalization removes exactly the twelve stop words", () => {
        assert.deepEqual(words("a an the and or is are of to in for with JAVA 25", "A an THE and or IS are of to in for with java 25"), ["JAVA", "25"]);
    });
    await test("ASCII boundaries reject substrings and retain digits", () => {
        assert.deepEqual(words("javascript java JAVA java25 25 125 _java_", "java 25"), ["java", "JAVA", "25", "java"]);
    });
    await test("Repeated and overlapping query terms mark each complete token once", () => {
        assert.deepEqual(words("java java javascript", "java JAVA java javascript script"), ["java", "java", "javascript"]);
    });
    await test("Punctuation, quotes, ampersands and markup remain original text", () => {
        const source = '<script>JAVA & "java"</script>\njava.';
        const document = dom(); const target = document.createElement("pre");
        highlight.render(target, source, highlight.ranges(source, "java", "keyword"));
        assert.equal(target.textContent, source);
        assert.deepEqual(target.children.filter(node => node.tagName === "mark").map(node => node.textContent), ["JAVA", "java", "java"]);
        assert.ok(target.children.every(node => ["mark", "#text"].includes(node.tagName)));
    });
    await test("Complete normalized phrase includes punctuation-separated tokens only", () => {
        assert.deepEqual(words("Java, programming; java alone; JAVA programming.", "java programming", "phrase"), ["Java", "programming", "JAVA", "programming"]);
    });
    await test("Interrupted, reversed and partial phrases do not highlight", () => {
        for (const source of ["java other programming", "programming java", "java", "programming", "javascript programming"]) {
            assert.deepEqual(words(source, "java programming", "phrase"), []);
        }
    });
    await test("Phrase matching retains stop words", () => {
        assert.deepEqual(words("The, Java; java; the other java", "the java", "phrase"), ["The", "Java"]);
        assert.deepEqual(words("the and", "the and", "phrase"), ["the", "and"]);
    });
    await test("Overlapping phrase occurrences do not create overlapping marks", () => {
        assert.deepEqual(words("java java java", "java java", "phrase"), ["java", "java", "java"]);
    });
    await test("Boolean AND-before-OR selects only satisfied complete alternatives", () => {
        assert.deepEqual(words("java python", "computer AND java OR python", "boolean"), ["python"]);
        assert.deepEqual(words("java python", "computer AND java OR computer AND python", "boolean"), []);
        assert.deepEqual(words("computer java", "computer AND java OR computer AND python", "boolean"), ["computer", "java"]);
    });
    await test("Boolean AND satisfaction is document-wide, not a local passage", () => {
        const source = "JAVA " + "distant passage. ".repeat(40) + "PYTHON";
        assert.deepEqual(words(source, "java aNd python OR absent", "boolean"), ["JAVA", "PYTHON"]);
    });
    await test("Boolean operators never become terms and repeated operands deduplicate", () => {
        assert.deepEqual(words("AND java OR java", "JAVA AND java", "boolean"), ["java", "java"]);
    });
    await test("Invalid Boolean grammar, stop words and unsupported operators fail plain", () => {
        for (const query of ["java AND", "OR java", "java python", "java NOT python", "java XOR python", "(java)", '"java"', "java*", "java?", "the", "java AND a", "java AND OR", "java|python", "java AND python OR"]) {
            assert.deepEqual(words("java python the", query, "boolean"), [], query);
        }
    });
    await test("Compatible punctuation-normalized raw Boolean operands work", () => {
        assert.deepEqual(words("JAVA python", "java, OR python", "boolean"), ["JAVA", "python"]);
    });
    await test("Empty, missing, malformed and unknown queries never fabricate marks", () => {
        for (const query of ["", "  ", null, undefined, "!!!", "absent"]) {
            for (const mode of ["keyword", "phrase", "boolean"]) { assert.deepEqual(words("Java", query, mode), []); }
        }
        assert.deepEqual(words("java", "java", "semantic"), []);
        assert.deepEqual(highlight.ranges(null, "java", "keyword"), []);
    });
    await test("Unicode surrounds ASCII tokens without corrupting offsets", () => {
        const source = "😀বাংলাJAVA😀éjavaé";
        assert.deepEqual(words(source, "java"), ["JAVA", "java"]);
        assert.equal(highlight.ranges(source, "java", "keyword")[0].start, source.indexOf("JAVA"));
    });
    await test("Length-changing case normalization conservatively omits highlights", () => {
        assert.deepEqual(words("İ java", "java"), []);
        assert.deepEqual(words("java", "İjava"), []);
    });
    await test("Short snippets preserve every original character without ellipses", () => {
        const source = "Java, <hello>\n& world."; const excerpt = highlight.snippet(source, highlight.ranges(source, "java", "keyword"));
        assert.equal(excerpt.text, source); assert.equal(excerpt.leading, false); assert.equal(excerpt.trailing, false);
    });
    for (const position of ["beginning", "middle", "end"]) {
        await test("Snippet at " + position + " is a bounded original window with truthful ellipses", () => {
            const source = position === "beginning" ? "java " + "x ".repeat(300) : position === "end" ? "x ".repeat(300) + "java" : "x ".repeat(150) + "java " + "x ".repeat(150);
            const excerpt = highlight.snippet(source, highlight.ranges(source, "java", "keyword"));
            assert.equal(excerpt.text, source.slice(excerpt.start, excerpt.end));
            assert.ok(excerpt.text.length >= 160 && excerpt.text.length <= 220);
            assert.equal(excerpt.leading, excerpt.start > 0); assert.equal(excerpt.trailing, excerpt.end < source.length);
            assert.equal(excerpt.leading, position !== "beginning"); assert.equal(excerpt.trailing, position !== "end");
            assert.ok(excerpt.ranges.length > 0);
        });
    }
    await test("Snippet boundaries never split a verified token", () => {
        const token = "a".repeat(300); const source = "java " + token + " rest";
        const excerpt = highlight.snippet(source, highlight.ranges(source, "java " + token, "keyword"));
        assert.ok(excerpt.text.includes(token)); assert.equal(excerpt.ranges[1].end - excerpt.ranges[1].start, 300);
    });
    await test("Snippet boundaries preserve Unicode surrogate pairs", () => {
        const source = "java " + "x".repeat(194) + "😀" + "x".repeat(100);
        const excerpt = highlight.snippet(source, highlight.ranges(source, "java", "keyword"));
        assert.equal(excerpt.end, 201); assert.ok(excerpt.text.endsWith("😀"));
        const second = "x".repeat(129) + "😀" + "x".repeat(69) + " java " + "x".repeat(300);
        const window = highlight.snippet(second, highlight.ranges(second, "java", "keyword"));
        assert.notEqual(window.start, 130);
    });
    await test("Empty documents and missing trustworthy matches have no fabricated excerpt", () => {
        assert.equal(highlight.snippet("", []), null); assert.equal(highlight.snippet("original", []), null);
    });
    await test("Renderer preserves whitespace and adds ellipses only on request", () => {
        const target = dom().createElement("pre"); const source = " JAVA\njava ";
        highlight.render(target, source, highlight.ranges(source, "java", "keyword"), true, true);
        assert.equal(target.textContent, "…" + source + "…");
        assert.ok(target.children.filter(node => node.tagName === "mark").every(node => node.textContent.length > 0));
    });
    await test("Reader fetches validated name only, preserves text and encodes original actions", async () => {
        const name = 'notes + 50% & বাংলা.txt'; const text = 'Java, programming.\n<script>& "original"</script>';
        const page = await reader("?" + new URLSearchParams({ name, q: "java programming", mode: "phrase" }), text);
        assert.deepEqual(page.requests, ["/document/view?" + new URLSearchParams({ name })]);
        assert.equal(page.elements["document-name"].textContent, name);
        assert.equal(page.elements["document-text"].textContent, text);
        assert.equal(page.elements["download-original"].attributes.href, "/document/download?" + new URLSearchParams({ name }));
        assert.equal(page.elements["view-raw"].attributes.href, page.requests[0]);
        assert.equal(page.elements["document-text"].attributes["aria-busy"], "false");
        assert.deepEqual(page.elements["document-text"].children.filter(node => node.tagName === "mark").map(node => node.textContent), ["Java", "programming"]);
    });
    await test("Reader rejects missing, duplicate, blank, traversal and control names without fetching", async () => {
        for (const query of ["", "?q=java", "?name=", "?name=+", "?name=a&name=b", "?name=..", "?name=.", "?name=..%2Fa", "?name=C%3A%5Ca", "?name=%00", "?name=%C2%85"]) {
            const page = await reader(query);
            assert.deepEqual(page.requests, [], query); assert.equal(page.elements["reader-status"].textContent, "Invalid document name.");
            assert.equal(page.elements["download-original"].hidden, true);
        }
    });
    for (const [label, response, message] of [["missing document", { ok: false, status: 404 }, "Document not found."], ["invalid document", { ok: false, status: 400 }, "Document unavailable."], ["network failure", { error: true }, "Document unavailable."], ["body failure", { ok: true, bodyError: true }, "Document unavailable."]]) {
        await test("Reader handles " + label + " without uncaught errors or content", async () => {
            const page = await reader("?name=a.txt&q=java&mode=keyword", "secret", response);
            assert.equal(page.elements["reader-status"].textContent, message); assert.equal(page.elements["document-text"].textContent, "");
            assert.equal(page.elements["document-text"].attributes["aria-busy"], "false");
        });
    }
    await test("Invalid or absent highlighting parameters keep reader plain and complete", async () => {
        for (const query of ["", "&q=", "&q=java", "&q=java&mode=unknown", "&q=java&q=python&mode=keyword", "&q=java&mode=keyword&mode=phrase", "&q=java+AND&mode=boolean"]) {
            const page = await reader("?name=a.txt" + query, "Java\noriginal"); const target = page.elements["document-text"];
            assert.equal(target.textContent, "Java\noriginal"); assert.equal(target.children.filter(node => node.tagName === "mark").length, 0);
        }
    });
    await test("Reader handles empty document and markup-like names as text", async () => {
        const page = await reader("?" + new URLSearchParams({ name: '<img onerror="x">.txt', q: "java", mode: "keyword" }), "");
        assert.equal(page.elements["document-name"].textContent, '<img onerror="x">.txt');
        assert.equal(page.elements["reader-status"].textContent, "Empty document.");
    });
    await test("Static pages load shared logic before their client and use only safe DOM operations", () => {
        for (const [html, client] of [["web/index.html", "/app.js"], ["web/reader.html", "/reader.js"]]) {
            const source = fs.readFileSync(html, "utf8"); assert.ok(source.indexOf('/highlight.js') < source.indexOf(client));
            assert.doesNotMatch(source, /<script(?![^>]*src=)[^>]*>/);
        }
        for (const file of ["web/app.js", "web/reader.js", "web/highlight.js"]) { assert.doesNotMatch(fs.readFileSync(file, "utf8"), /innerHTML|outerHTML|insertAdjacentHTML|document\.write/); }
        assert.match(fs.readFileSync("web/styles.css", "utf8"), /#document-text[\s\S]*?white-space: pre-wrap/);
    });
    await test("Reader visual states distinguish original text, empty documents and failures", async () => {
        for (const [text, response, state] of [["Java", { ok: true }, "ready"], ["", { ok: true }, "empty"], ["", { ok: false, status: 404 }, "error"], ["", { error: true }, "error"]]) {
            const page = await reader("?name=a.txt&q=java&mode=keyword", text, response);
            assert.equal(page.elements["reader-status"].attributes["data-state"], state);
        }
        const invalid = await reader("?name=../a.txt");
        assert.equal(invalid.elements["reader-status"].attributes["data-state"], "error");
    });
    await test("Shared design preserves skip links, local assets and reduced-motion support", () => {
        for (const [file, target] of [["web/index.html", "main-content"], ["web/reader.html", "document-text"]]) {
            const html = fs.readFileSync(file, "utf8");
            assert.ok(html.includes('class="skip-link" href="#' + target + '"'));
            assert.ok(html.includes('id="' + target + '"'));
            assert.doesNotMatch(html, /(?:src|href)="https?:/);
        }
        const css = fs.readFileSync("web/styles.css", "utf8");
        assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
        assert.match(css, /animation: none !important/);
        assert.match(css, /transition: none !important/);
        assert.match(css, /a:focus-visible/);
    });
    console.log("Total passed: " + passed + "; total failed: 0");
}
main().catch(error => { console.error(error); process.exitCode = 1; });
