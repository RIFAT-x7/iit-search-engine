// Dependency-free DOM/fetch model; these checks do not replace real browser testing.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync("web/app.js", "utf8");
let passed = 0;

function element() {
    return {
        textContent: "",
        children: [],
        attributes: {},
        listeners: {},
        disabled: false,
        addEventListener(type, callback) { this.listeners[type] = callback; },
        replaceChildren() { this.children = []; },
        appendChild(child) { this.children.push(child); },
        setAttribute(name, value) { this.attributes[name] = value; },
        set innerHTML(value) { throw new Error("Unsafe HTML rendering: " + value); }
    };
}

function client() {
    const form = element();
    const query = element();
    const button = element();
    const status = element();
    const list = element();
    const keywordLimit = Object.assign(element(), { value: "", disabled: true });
    const radios = [Object.assign(element(), { value: "keyword", checked: true }),
        Object.assign(element(), { value: "phrase", checked: false }),
        Object.assign(element(), { value: "boolean", checked: false })];
    const requests = [];
    form.querySelector = (selector) => selector.startsWith("button") ? button : radios.find(radio => radio.checked);
    form.querySelectorAll = () => radios;
    const elements = { "search-form": form, query, "search-status": status, "result-list": list,
        "keyword-limit": keywordLimit };
    const context = {
        document: { getElementById: id => elements[id], createElement: () => element() },
        URLSearchParams,
        AbortController,
        fetch(url, options) {
            return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject }));
        }
    };
    vm.runInNewContext(source, context);
    return {
        query, button, status, list, requests, keywordLimit,
        limit(value) {
            assert.equal(keywordLimit.disabled, false, "A disabled native selector cannot be changed by the user");
            keywordLimit.value = value;
            keywordLimit.listeners.change();
        },
        submit(text) {
            query.value = text;
            return form.listeners.submit({ preventDefault() {} });
        },
        mode(value) {
            for (const radio of radios) { radio.checked = radio.value === value; }
            radios.find(radio => radio.checked).listeners.change();
        },
        respond(index, data, ok = true) {
            requests[index].resolve({ ok, async json() { return data; } });
        },
        texts() { return list.children.map(item => item.textContent); }
    };
}

async function test(name, check) {
    await check();
    passed++;
    console.log("PASS: " + name);
}

async function main() {
    await test("Keyword requests preserve numeric score display and order", async () => {
        const page = client();
        const pending = page.submit("java & search");
        const params = new URL(page.requests[0].url, "http://localhost").searchParams;
        assert.equal(params.get("q"), "java & search");
        assert.equal(params.get("mode"), "keyword");
        assert.equal(page.button.disabled, true);
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(0, { results: [{ filename: "z.txt", score: 5.541893581161611 }, { filename: "a.txt", score: 1.8472978603872037 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt - Score: 5.54", "a.txt - Score: 1.85"]);
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("Phrase results render filename-only entries safely", async () => {
        const page = client();
        page.mode("phrase");
        const pending = page.submit("java search");
        assert.equal(new URL(page.requests[0].url, "http://localhost").searchParams.get("mode"), "phrase");
        const unsafeLookingName = '<img src=x onerror="alert(1)">.txt';
        page.respond(0, { mode: "phrase", results: [{ filename: unsafeLookingName }, { filename: "z.txt" }] });
        await pending;
        assert.deepEqual(page.texts(), [unsafeLookingName, "z.txt"]);
        assert.equal(page.status.textContent, "2 phrase match(es) found.");
    });
    await test("Empty results replace earlier content", async () => {
        const page = client();
        let pending = page.submit("java");
        page.respond(0, { results: [{ filename: "a.txt", score: 1 }] });
        await pending;
        pending = page.submit("unknown");
        assert.deepEqual(page.texts(), []);
        page.respond(1, { results: [] });
        await pending;
        assert.equal(page.status.textContent, "No results found.");
    });
    await test("JSON errors display as text and restore controls", async () => {
        const page = client();
        const pending = page.submit("   ");
        page.respond(0, { error: "Bad request: <invalid>" }, false);
        await pending;
        assert.equal(page.status.textContent, "Bad request: <invalid>");
        assert.deepEqual(page.texts(), []);
        assert.equal(page.button.disabled, false);
    });
    await test("Network failures restore controls", async () => {
        const page = client();
        const pending = page.submit("java");
        page.requests[0].reject(new Error("offline"));
        await pending;
        assert.match(page.status.textContent, /Unable to load/);
        assert.equal(page.button.disabled, false);
    });
    await test("Invalid JSON restores controls", async () => {
        const page = client();
        const pending = page.submit("java");
        page.requests[0].resolve({ ok: true, async json() { throw new Error("invalid JSON"); } });
        await pending;
        assert.match(page.status.textContent, /Unable to load/);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("Switching mode clears completed results and status", async () => {
        const page = client();
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "a.txt", score: 2 }] });
        await pending;
        page.mode("phrase");
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
        assert.equal(page.button.disabled, false);
    });
    await test("Late success cannot overwrite a new pending mode", async () => {
        const page = client();
        const old = page.submit("java");
        page.mode("phrase");
        assert.equal(page.requests[0].options.signal.aborted, true);
        const current = page.submit("java search");
        // Model a response that arrives despite cancellation.
        page.respond(0, { results: [{ filename: "old.txt", score: 3 }] });
        await old;
        assert.deepEqual(page.texts(), []);
        assert.equal(page.button.disabled, true);
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(1, { results: [{ filename: "new.txt" }] });
        await current;
        assert.deepEqual(page.texts(), ["new.txt"]);
    });
    await test("Late failure cannot overwrite a newer completed result", async () => {
        const page = client();
        const old = page.submit("java");
        page.mode("phrase");
        const current = page.submit("java search");
        page.respond(1, { results: [{ filename: "new.txt" }] });
        await current;
        page.requests[0].reject(new Error("old failure"));
        await old;
        assert.deepEqual(page.texts(), ["new.txt"]);
        assert.equal(page.status.textContent, "1 phrase match(es) found.");
    });
    await test("Duplicate submission while busy sends no additional request", async () => {
        const page = client();
        const pending = page.submit("java");
        await page.submit("search");
        assert.equal(page.requests.length, 1);
        page.respond(0, { results: [] });
        await pending;
    });
    await test("Switching back to keyword mode renders scores again", async () => {
        const page = client();
        page.mode("phrase");
        const old = page.submit("java search");
        page.mode("keyword");
        const current = page.submit("java");
        page.respond(1, { results: [{ filename: "keyword.txt", score: 2.75 }] });
        await current;
        page.respond(0, { results: [{ filename: "stale.txt" }] });
        await old;
        assert.deepEqual(page.texts(), ["keyword.txt - Score: 2.75"]);
    });
    await test("Native GET form retains labeled mode controls and required query", async () => {
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.match(html, /<form[^>]*action="\/search"[^>]*method="get"/);
        assert.match(html, /<fieldset[^>]*>[\s\S]*<legend>Search mode<\/legend>/);
        assert.match(html, /<label><input type="radio" name="mode" value="keyword" checked> Keyword Search<\/label>/);
        assert.match(html, /<label><input type="radio" name="mode" value="phrase"> Exact Phrase Search<\/label>/);
        assert.match(html, /<label for="query">Search text<\/label>/);
        assert.match(html, /<input type="search"[^>]*name="q"[^>]*required>/);
        assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML/);
    });
    await test("Third native radio and help describe Boolean syntax without changing GET fallback", async () => {
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.equal([...html.matchAll(/type="radio" name="mode"/g)].length, 3);
        assert.match(html, /<label><input type="radio" name="mode" value="boolean"> Boolean Search<\/label>/);
        assert.match(html, /AND before OR/);
        assert.match(html, /parentheses and NOT are not supported/);
        assert.match(html, /aria-describedby="search-help"/);
        assert.equal([...html.matchAll(/type="radio"[^>]* checked/g)].length, 1);
    });
    await test("Boolean requests encode the expression and render filename-only results safely", async () => {
        const page = client();
        page.mode("boolean");
        const expression = "JAVA, and programming";
        const pending = page.submit(expression);
        const params = new URL(page.requests[0].url, "http://localhost").searchParams;
        assert.equal(params.get("q"), expression);
        assert.equal(params.get("mode"), "boolean");
        const filename = '<img src=x onerror="alert(1)">.txt';
        page.respond(0, { mode: "boolean", results: [{ filename }, { filename: "z.txt" }] });
        await pending;
        assert.deepEqual(page.texts(), [filename, "z.txt"]);
        assert.equal(page.status.textContent, "2 Boolean match(es) found.");
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("Malformed Boolean diagnostics render as text and allow a corrected submission", async () => {
        const page = client();
        page.mode("boolean");
        let pending = page.submit('java AND "<invalid>"');
        const error = 'Bad request: Unsupported Boolean syntax in operand: "<invalid>"';
        page.respond(0, { error }, false);
        await pending;
        assert.equal(page.status.textContent, error);
        assert.deepEqual(page.texts(), []);
        assert.equal(page.button.disabled, false);
        pending = page.submit("java AND programming");
        page.respond(1, { mode: "boolean", results: [{ filename: "valid.txt" }] });
        await pending;
        assert.deepEqual(page.texts(), ["valid.txt"]);
        assert.equal(page.status.textContent, "1 Boolean match(es) found.");
    });
    await test("Boolean empty results replace earlier ranked content", async () => {
        const page = client();
        let pending = page.submit("java");
        page.respond(0, { results: [{ filename: "ranked.txt", score: 2 }] });
        await pending;
        page.mode("boolean");
        pending = page.submit("java AND absent");
        page.respond(1, { mode: "boolean", results: [] });
        await pending;
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "No results found.");
    });
    await test("Switching from Boolean to phrase to keyword clears old content and restores scores", async () => {
        const page = client();
        page.mode("boolean");
        let pending = page.submit("java OR search");
        page.respond(0, { results: [{ filename: "boolean.txt" }] });
        await pending;
        page.mode("phrase");
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
        pending = page.submit("java search");
        page.respond(1, { results: [{ filename: "phrase.txt" }] });
        await pending;
        assert.deepEqual(page.texts(), ["phrase.txt"]);
        assert.equal(page.status.textContent, "1 phrase match(es) found.");
        page.mode("keyword");
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
        pending = page.submit("java");
        page.respond(2, { results: [{ filename: "ranked.txt", score: 3.25 }] });
        await pending;
        assert.deepEqual(page.texts(), ["ranked.txt - Score: 3.25"]);
    });
    await test("Late Boolean success cannot render or unlock a newer keyword request", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.submit("java OR search");
        page.mode("keyword");
        assert.equal(page.requests[0].options.signal.aborted, true);
        const current = page.submit("java");
        page.respond(0, { results: [{ filename: "stale.txt" }] });
        await old;
        assert.deepEqual(page.texts(), []);
        assert.equal(page.button.disabled, true);
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(1, { results: [{ filename: "new.txt", score: 1.25 }] });
        await current;
        assert.deepEqual(page.texts(), ["new.txt - Score: 1.25"]);
    });
    await test("Late Boolean error cannot overwrite newer phrase results", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.submit("java AND");
        page.mode("phrase");
        const current = page.submit("java search");
        page.respond(1, { results: [{ filename: "phrase.txt" }] });
        await current;
        page.respond(0, { error: "Bad request: Missing operand after AND." }, false);
        await old;
        assert.deepEqual(page.texts(), ["phrase.txt"]);
        assert.equal(page.status.textContent, "1 phrase match(es) found.");
    });
    await test("Stale keyword failure and phrase success cannot overwrite current Boolean results", async () => {
        const page = client();
        const keyword = page.submit("java");
        page.mode("phrase");
        const phrase = page.submit("java search");
        page.mode("boolean");
        const current = page.submit("java AND programming");
        assert.equal(page.requests[0].options.signal.aborted, true);
        assert.equal(page.requests[1].options.signal.aborted, true);
        page.respond(2, { results: [{ filename: "boolean.txt" }] });
        await current;
        page.requests[0].reject(new Error("obsolete keyword failure"));
        page.respond(1, { results: [{ filename: "stale-phrase.txt" }] });
        await Promise.all([keyword, phrase]);
        assert.deepEqual(page.texts(), ["boolean.txt"]);
        assert.equal(page.status.textContent, "1 Boolean match(es) found.");
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("All results initializes enabled in keyword mode and omits k, including after a limited search", async () => {
        const page = client();
        assert.equal(page.keywordLimit.disabled, false);
        assert.equal(page.keywordLimit.value, "");
        let pending = page.submit("java");
        assert.equal(new URL(page.requests[0].url, "http://localhost").searchParams.has("k"), false);
        page.respond(0, { results: [{ filename: "a.txt", score: 2 }] });
        await pending;
        assert.equal(page.status.textContent, "1 result(s) found.");
        page.limit("3");
        pending = page.submit("java");
        page.respond(1, { results: [] });
        await pending;
        page.limit("");
        pending = page.submit("java");
        assert.equal(new URL(page.requests[2].url, "http://localhost").searchParams.has("k"), false);
        page.respond(2, { results: [] });
        await pending;
    });
    await test("Top 1, 3, 5 and 10 send exactly one numeric k without corrupting query encoding", async () => {
        for (const value of ["1", "3", "5", "10"]) {
            const page = client();
            page.limit(value);
            const query = 'Java & search + ? "Unicode: \u09ac"';
            const pending = page.submit(query);
            const params = new URL(page.requests[0].url, "http://localhost").searchParams;
            assert.equal(params.get("q"), query);
            assert.equal(params.get("mode"), "keyword");
            assert.deepEqual(params.getAll("k"), [value]);
            page.respond(0, { results: [] });
            await pending;
        }
    });
    await test("Top-K preserves server order and score formatting with an explicit limited-count status", async () => {
        const page = client();
        page.limit("3");
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "z.txt", score: 10 },
            { filename: "a.txt", score: 5.541893581161611 }, { filename: "m.txt", score: 1.8472978603872037 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt - Score: 10.00", "a.txt - Score: 5.54", "m.txt - Score: 1.85"]);
        assert.equal(page.status.textContent, "Showing 3 ranked result(s) (Top 3 limit).");
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("Fewer-than-K status reports returned count without claiming total matches", async () => {
        const page = client();
        page.limit("10");
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "java_programming.txt", score: 5.541893581161611 },
            { filename: "academic_notice.txt", score: 1.8472978603872037 }] });
        await pending;
        assert.equal(page.status.textContent, "Showing 2 ranked result(s) (Top 10 limit).");
        assert.equal(page.texts().length, 2);
    });
    await test("Empty Top-K results clear earlier results and retain the existing empty message", async () => {
        const page = client();
        page.limit("1");
        let pending = page.submit("java");
        page.respond(0, { results: [{ filename: "a.txt", score: 1 }] });
        await pending;
        pending = page.submit("unknown");
        assert.deepEqual(page.texts(), []);
        page.respond(1, { results: [] });
        await pending;
        assert.equal(page.status.textContent, "No results found.");
        assert.equal(page.keywordLimit.value, "1");
    });
    await test("Phrase mode disables but preserves the keyword limit and never sends k", async () => {
        const page = client();
        page.limit("5");
        page.mode("phrase");
        assert.equal(page.keywordLimit.disabled, true);
        assert.equal(page.keywordLimit.value, "5");
        const pending = page.submit("java search");
        const params = new URL(page.requests[0].url, "http://localhost").searchParams;
        assert.equal(params.get("mode"), "phrase");
        assert.equal(params.has("k"), false);
        page.respond(0, { results: [{ filename: "phrase.txt" }] });
        await pending;
        assert.equal(page.status.textContent, "1 phrase match(es) found.");
        assert.deepEqual(page.texts(), ["phrase.txt"]);
    });
    await test("Boolean mode disables but preserves the keyword limit and never sends k", async () => {
        const page = client();
        page.limit("10");
        page.mode("boolean");
        assert.equal(page.keywordLimit.disabled, true);
        assert.equal(page.keywordLimit.value, "10");
        const pending = page.submit("java AND programming");
        const params = new URL(page.requests[0].url, "http://localhost").searchParams;
        assert.equal(params.get("mode"), "boolean");
        assert.equal(params.has("k"), false);
        page.respond(0, { results: [{ filename: "boolean.txt" }] });
        await pending;
        assert.equal(page.status.textContent, "1 Boolean match(es) found.");
        assert.deepEqual(page.texts(), ["boolean.txt"]);
    });
    await test("Returning through phrase and Boolean to keyword restores the previous limit", async () => {
        const page = client();
        page.limit("3");
        page.mode("phrase");
        page.mode("boolean");
        page.mode("keyword");
        assert.equal(page.keywordLimit.disabled, false);
        assert.equal(page.keywordLimit.value, "3");
        const pending = page.submit("java");
        assert.equal(new URL(page.requests[0].url, "http://localhost").searchParams.get("k"), "3");
        page.respond(0, { results: [] });
        await pending;
    });
    await test("Changing the limit clears completed content and status without submitting", async () => {
        const page = client();
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "old.txt", score: 4 }] });
        await pending;
        page.limit("5");
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
        assert.equal(page.requests.length, 1);
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("Limit changes abort pending search and restore controls before any new submission", async () => {
        const page = client();
        const old = page.submit("java");
        assert.equal(page.keywordLimit.disabled, false, "Limit must remain usable while searching");
        page.limit("1");
        assert.equal(page.requests[0].options.signal.aborted, true);
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
        page.respond(0, { results: [{ filename: "obsolete.txt", score: 1 }] });
        await old;
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
    });
    await test("Late success after a K change cannot render or unlock a newer pending search", async () => {
        const page = client();
        page.limit("1");
        const old = page.submit("java");
        page.limit("3");
        const current = page.submit("search");
        page.respond(0, { results: [{ filename: "obsolete.txt", score: 1 }] });
        await old;
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "Searching...");
        assert.equal(page.button.disabled, true);
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(1, { results: [{ filename: "current.txt", score: 2 }] });
        await current;
        assert.equal(page.status.textContent, "Showing 1 ranked result(s) (Top 3 limit).");
    });
    await test("Late success after a K change cannot overwrite a newer completed search", async () => {
        const page = client();
        const old = page.submit("java");
        page.limit("5");
        const current = page.submit("search");
        page.respond(1, { results: [{ filename: "current.txt", score: 2 }] });
        await current;
        page.respond(0, { results: [{ filename: "obsolete.txt", score: 9 }] });
        await old;
        assert.deepEqual(page.texts(), ["current.txt - Score: 2.00"]);
        assert.equal(page.status.textContent, "Showing 1 ranked result(s) (Top 5 limit).");
    });
    await test("Late network failure after a K change cannot unlock the current request", async () => {
        const page = client();
        const old = page.submit("java");
        page.limit("3");
        const current = page.submit("search");
        page.requests[0].reject(new Error("obsolete failure"));
        await old;
        assert.equal(page.status.textContent, "Searching...");
        assert.equal(page.button.disabled, true);
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(1, { results: [] });
        await current;
    });
    await test("Late server error after a K change cannot replace completed content", async () => {
        const page = client();
        const old = page.submit("java");
        page.limit("1");
        const current = page.submit("search");
        page.respond(1, { results: [{ filename: "current.txt", score: 2 }] });
        await current;
        page.respond(0, { error: "Bad request: obsolete" }, false);
        await old;
        assert.deepEqual(page.texts(), ["current.txt - Score: 2.00"]);
        assert.equal(page.status.textContent, "Showing 1 ranked result(s) (Top 1 limit).");
        assert.equal(page.button.disabled, false);
    });
    await test("A K change during JSON parsing invalidates the old response too", async () => {
        const page = client();
        const old = page.submit("java");
        let finishJson;
        let markParsingStarted;
        const parsingStarted = new Promise(resolve => { markParsingStarted = resolve; });
        page.requests[0].resolve({ ok: true, json() {
            return new Promise(resolve => { finishJson = resolve; markParsingStarted(); });
        } });
        await parsingStarted;
        assert.equal(typeof finishJson, "function");
        page.limit("10");
        const current = page.submit("search");
        page.respond(1, { results: [{ filename: "current.txt", score: 3 }] });
        await current;
        finishJson({ results: [{ filename: "obsolete.txt", score: 9 }] });
        await old;
        assert.deepEqual(page.texts(), ["current.txt - Score: 3.00"]);
        assert.equal(page.status.textContent, "Showing 1 ranked result(s) (Top 10 limit).");
    });
    await test("Top-K preserves duplicate-submit prevention while busy", async () => {
        const page = client();
        page.limit("3");
        const pending = page.submit("java");
        await page.submit("search");
        assert.equal(page.requests.length, 1);
        page.respond(0, { results: [] });
        await pending;
    });
    await test("Top-K server errors render as text and keep the selected limit for retry", async () => {
        const page = client();
        page.limit("5");
        const pending = page.submit("java");
        page.respond(0, { error: 'Bad request: <img src=x onerror="alert(1)">' }, false);
        await pending;
        assert.equal(page.status.textContent, 'Bad request: <img src=x onerror="alert(1)">');
        assert.deepEqual(page.texts(), []);
        assert.equal(page.keywordLimit.value, "5");
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
        const retry = page.submit("java");
        assert.equal(new URL(page.requests[1].url, "http://localhost").searchParams.get("k"), "5");
        page.respond(1, { results: [] });
        await retry;
    });
    await test("Top-K network failures, invalid JSON and invalid result shapes restore controls safely", async () => {
        for (const kind of ["network", "json", "shape"]) {
            const page = client();
            page.limit("3");
            const pending = page.submit("java");
            if (kind === "network") {
                page.requests[0].reject(new Error("offline"));
            } else if (kind === "json") {
                page.requests[0].resolve({ ok: true, async json() { throw new Error("invalid JSON"); } });
            } else {
                page.respond(0, { results: null });
            }
            await pending;
            assert.match(page.status.textContent, /Unable to load/);
            assert.deepEqual(page.texts(), []);
            assert.equal(page.button.disabled, false);
            assert.equal(page.list.attributes["aria-busy"], "false");
            assert.equal(page.keywordLimit.disabled, false);
            assert.equal(page.keywordLimit.value, "3");
        }
    });
    await test("Top-K filenames are rendered only as safe text", async () => {
        const page = client();
        page.limit("1");
        const pending = page.submit("java");
        const filename = '<img src=x onerror="alert(1)">.txt';
        page.respond(0, { results: [{ filename, score: 2.75 }] });
        await pending;
        assert.deepEqual(page.texts(), [filename + " - Score: 2.75"]);
    });
    await test("The browser renders the returned payload without client-side Top-K truncation", async () => {
        const page = client();
        page.limit("1");
        const pending = page.submit("java");
        // Deliberately oversized mocked payload: real server enforces the limit.
        page.respond(0, { results: [{ filename: "z.txt", score: 3 }, { filename: "a.txt", score: 2 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt - Score: 3.00", "a.txt - Score: 2.00"]);
        assert.doesNotMatch(source, /\.slice\(|\.splice\(|\.sort\(/);
    });
    await test("Malformed scores cannot leave partially rendered Top-K results", async () => {
        const page = client();
        page.limit("3");
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "valid.txt", score: 2 }, { filename: "invalid.txt" }] });
        await pending;
        assert.match(page.status.textContent, /Unable to load/);
        assert.deepEqual(page.texts(), []);
        assert.equal(page.button.disabled, false);
        assert.equal(page.list.attributes["aria-busy"], "false");
    });
    await test("Native Top-K select has an explicit label, fixed choices and safe no-JavaScript fallback", async () => {
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.match(html, /<label for="keyword-limit">Keyword result limit<\/label>/);
        const select = html.match(/<select\b([^>]*)>([\s\S]*?)<\/select>/);
        assert.ok(select);
        assert.match(select[1], /id="keyword-limit"/);
        assert.match(select[1], /aria-describedby="keyword-limit-help"/);
        assert.match(select[1], /\bdisabled\b/);
        assert.doesNotMatch(select[1], /\bname\s*=/);
        const options = [...select[2].matchAll(/<option value="([^"]*)"([^>]*)>([^<]+)<\/option>/g)];
        assert.deepEqual(options.map(option => [option[1], option[3]]),
            [["", "All results"], ["1", "Top 1"], ["3", "Top 3"], ["5", "Top 5"], ["10", "Top 10"]]);
        assert.match(options[0][2], /\bselected\b/);
        assert.equal(options.filter(option => /\bselected\b/.test(option[2])).length, 1);
        assert.match(html, /id="keyword-limit-help"[\s\S]*Keyword limits require JavaScript/);
        assert.match(html, /Without it, searches remain unrestricted/);
        assert.doesNotMatch(html, /name="k"/);
        assert.match(html, /<form[^>]*action="\/search"[^>]*method="get"/);
        const css = fs.readFileSync("web/styles.css", "utf8");
        assert.match(css, /select:focus-visible/);
        assert.match(css, /#keyword-limit:disabled/);
    });
    console.log("Total passed: " + passed + "; total failed: 0");
}

main().catch(failure => {
    console.error(failure);
    process.exitCode = 1;
});
