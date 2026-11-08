// Dependency-free DOM/fetch model; these checks do not replace real browser testing.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync("web/app.js", "utf8");
let passed = 0;

function element(tagName = "div", ownerDocument = null) {
    return {
        tagName,
        value: "",
        hidden: false,
        parentElement: null,
        _text: "",
        get textContent() { return this._text + this.children.map(child => child.textContent).join(""); },
        set textContent(value) { this.replaceChildren(); this._text = String(value); },
        children: [],
        attributes: {},
        listeners: {},
        disabled: false,
        addEventListener(type, callback) { this.listeners[type] = callback; },
        replaceChildren() {
            for (const child of this.children) { child.parentElement = null; }
            this.children = [];
            this._text = "";
        },
        appendChild(child) { child.parentElement = this; this.children.push(child); return child; },
        setAttribute(name, value) { this.attributes[name] = value; },
        focus() { ownerDocument.activeElement = this; },
        click() {
            for (let node = this; node; node = node.parentElement) {
                if (node.hidden || node.disabled) { return; }
            }
            if (this.listeners.click) { this.listeners.click(); }
        },
        set innerHTML(value) { throw new Error("Unsafe HTML rendering: " + value); }
    };
}

function client() {
    const document = { activeElement: null };
    const form = element();
    const query = element();
    const button = element();
    const status = element();
    const list = element();
    const keywordLimit = Object.assign(element(), { value: "", disabled: true });
    const rawQuery = element();
    const advanced = Object.assign(element("fieldset"), { hidden: true, disabled: true });
    const groupList = element();
    const addGroup = element("button");
    const preview = element();
    const validation = element();
    rawQuery.appendChild(query);
    form.appendChild(rawQuery);
    form.appendChild(advanced);
    advanced.appendChild(groupList);
    advanced.appendChild(addGroup);
    advanced.appendChild(preview);
    advanced.appendChild(validation);
    const radios = [Object.assign(element(), { value: "keyword", checked: true }),
        Object.assign(element(), { value: "phrase", checked: false }),
        Object.assign(element(), { value: "boolean", checked: false })];
    const requests = [];
    form.querySelector = (selector) => selector.startsWith("button") ? button : radios.find(radio => radio.checked);
    form.querySelectorAll = () => radios;
    const elements = { "search-form": form, query, "search-status": status, "result-list": list,
        "keyword-limit": keywordLimit, "raw-query": rawQuery, "advanced-search": advanced,
        "advanced-groups": groupList, "add-or-group": addGroup,
        "advanced-preview": preview, "advanced-validation": validation };
    document.getElementById = id => elements[id];
    document.createElement = tag => element(tag, document);
    const context = {
        document,
        URLSearchParams,
        AbortController,
        fetch(url, options) {
            return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject }));
        }
    };
    vm.runInNewContext(source, context);
    const groups = () => groupList.children.filter(node => node.tagName === "fieldset");
    const rows = g => groups()[g].children.filter(node => node.tagName === "div");
    const input = (g, w) => rows(g)[w].children.find(node => node.tagName === "input");
    const groupButton = (g, text) => groups()[g].children.find(node => node.tagName === "button" && node.textContent === text);
    function edit(g, w, value) {
        input(g, w).value = value;
        input(g, w).listeners.input();
    }
    return {
        query, button, status, list, requests, keywordLimit, document, rawQuery, advanced,
        preview, validation, groupList, groups, rows, input, edit, groupButton,
        addWord(g) { groupButton(g, "Add AND Word").click(); },
        addGroup() { addGroup.click(); },
        removeWord(g, w) { rows(g)[w].children.find(node => node.tagName === "button").click(); },
        removeGroup(g) { groupButton(g, "Remove Group").click(); },
        setGroups(values) {
            // Drive the same native controls as a user, rather than touching app state.
            while (groups().length > 1) { groupButton(groups().length - 1, "Remove Group").click(); }
            while (rows(0).length > 1) { rows(0)[rows(0).length - 1].children.find(node => node.tagName === "button").click(); }
            for (let g = 0; g < values.length; g++) {
                if (g > 0) { addGroup.click(); }
                for (let w = 0; w < values[g].length; w++) {
                    if (w > 0) { groupButton(g, "Add AND Word").click(); }
                    edit(g, w, values[g][w]);
                }
            }
        },
        limit(value) {
            assert.equal(keywordLimit.disabled, false, "A disabled native selector cannot be changed by the user");
            keywordLimit.value = value;
            keywordLimit.listeners.change();
        },
        submit(text) {
            if (text !== undefined) { query.value = text; }
            return form.listeners.submit({ preventDefault() {} });
        },
        searchGroups(values) { this.setGroups(values); return this.submit(); },
        mode(value) {
            for (const radio of radios) { radio.checked = radio.value === value; }
            radios.find(radio => radio.checked).listeners.change();
        },
        respond(index, data, ok = true) {
            requests[index].resolve({ ok, async json() { return data; } });
        },
        // Preserve existing filename/order assertions while checking link structure separately below.
        texts() { return list.children.map(item => item.children[0].textContent); }
    };
}

async function test(name, check) {
    await check();
    passed++;
    console.log("PASS: " + name);
}

async function main() {
    await test("Keyword requests hide numeric scores and preserve order", async () => {
        const page = client();
        const pending = page.submit("java & search");
        const params = new URL(page.requests[0].url, "http://localhost").searchParams;
        assert.equal(params.get("q"), "java & search");
        assert.equal(params.get("mode"), "keyword");
        assert.equal(page.button.disabled, true);
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(0, { results: [{ filename: "z.txt", score: 5.541893581161611 }, { filename: "a.txt", score: 1.8472978603872037 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt", "a.txt"]);
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
    await test("Switching back to keyword mode keeps scores hidden", async () => {
        const page = client();
        page.mode("phrase");
        const old = page.submit("java search");
        page.mode("keyword");
        const current = page.submit("java");
        page.respond(1, { results: [{ filename: "keyword.txt", score: 2.75 }] });
        await current;
        page.respond(0, { results: [{ filename: "stale.txt" }] });
        await old;
        assert.deepEqual(page.texts(), ["keyword.txt"]);
    });
    await test("Native GET form retains labeled mode controls and required query", async () => {
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.match(html, /<form[^>]*action="\/search"[^>]*method="get"/);
        assert.match(html, /<fieldset[^>]*>[\s\S]*<legend>Search mode<\/legend>/);
        assert.match(html, /<label><input type="radio" name="mode" value="keyword" checked> Basic Keyword Search<\/label>/);
        assert.match(html, /<label><input type="radio" name="mode" value="phrase"> Exact Phrase Search<\/label>/);
        assert.match(html, /<label for="query">Search text<\/label>/);
        assert.match(html, /<input type="search"[^>]*name="q"[^>]*required>/);
        assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML/);
    });
    await test("Third native radio and help describe Boolean syntax without changing GET fallback", async () => {
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.equal([...html.matchAll(/type="radio" name="mode"/g)].length, 3);
        assert.match(html, /<label><input type="radio" name="mode" value="boolean"> Advanced Search<\/label>/);
        assert.match(html, /AND before OR/);
        assert.match(html, /parentheses and NOT are not supported/);
        assert.match(html, /aria-describedby="search-help"/);
        assert.equal([...html.matchAll(/type="radio"[^>]* checked/g)].length, 1);
    });
    await test("Boolean requests encode the expression and render filename-only results safely", async () => {
        const page = client();
        page.mode("boolean");
        const expression = "JAVA AND programming";
        const pending = page.searchGroups([["JAVA", "programming"]]);
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
        let pending = page.searchGroups([["java", "programming"]]);
        const error = 'Bad request: Unsupported Boolean syntax in operand: "<invalid>"';
        page.respond(0, { error }, false);
        await pending;
        assert.equal(page.status.textContent, error);
        assert.deepEqual(page.texts(), []);
        assert.equal(page.button.disabled, false);
        pending = page.searchGroups([["java", "programming"]]);
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
        pending = page.searchGroups([["java", "absent"]]);
        page.respond(1, { mode: "boolean", results: [] });
        await pending;
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "No results found.");
    });
    await test("Switching from Boolean to phrase to keyword clears old content and keeps scores hidden", async () => {
        const page = client();
        page.mode("boolean");
        let pending = page.searchGroups([["java"], ["search"]]);
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
        assert.deepEqual(page.texts(), ["ranked.txt"]);
    });
    await test("Late Boolean success cannot render or unlock a newer keyword request", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.searchGroups([["java"], ["search"]]);
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
        assert.deepEqual(page.texts(), ["new.txt"]);
    });
    await test("Late Boolean error cannot overwrite newer phrase results", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.searchGroups([["java", "absent"]]);
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
        const current = page.searchGroups([["java", "programming"]]);
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
    await test("Top-K preserves server order without displaying scores with an explicit limited-count status", async () => {
        const page = client();
        page.limit("3");
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "z.txt", score: 10 },
            { filename: "a.txt", score: 5.541893581161611 }, { filename: "m.txt", score: 1.8472978603872037 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt", "a.txt", "m.txt"]);
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
        const pending = page.searchGroups([["java", "programming"]]);
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
        assert.deepEqual(page.texts(), ["current.txt"]);
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
        assert.deepEqual(page.texts(), ["current.txt"]);
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
        assert.deepEqual(page.texts(), ["current.txt"]);
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
        assert.deepEqual(page.texts(), [filename]);
    });
    await test("The browser renders the returned payload without client-side Top-K truncation", async () => {
        const page = client();
        page.limit("1");
        const pending = page.submit("java");
        // Deliberately oversized mocked payload: real server enforces the limit.
        page.respond(0, { results: [{ filename: "z.txt", score: 3 }, { filename: "a.txt", score: 2 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt", "a.txt"]);
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
    await test("Advanced Search starts with one empty word in one group and enhances the raw fallback", async () => {
        const page = client();
        assert.equal(page.advanced.hidden, true);
        assert.equal(page.advanced.disabled, true);
        assert.equal(page.rawQuery.hidden, false);
        assert.equal(page.query.disabled, false);
        page.mode("boolean");
        assert.equal(page.groups().length, 1);
        assert.equal(page.rows(0).length, 1);
        assert.equal(page.input(0, 0).value, "");
        assert.equal(page.advanced.hidden, false);
        assert.equal(page.advanced.disabled, false);
        assert.equal(page.rawQuery.hidden, true);
        assert.equal(page.query.disabled, true);
        assert.match(page.preview.textContent, /Incomplete or invalid/);
        page.mode("phrase");
        assert.equal(page.query.disabled, false);
        assert.equal(page.rawQuery.hidden, false);
        assert.equal(page.advanced.hidden, true);
        assert.equal(page.advanced.disabled, true);
    });
    await test("Add AND Word preserves existing input and focuses the new labeled word", async () => {
        const page = client();
        page.mode("boolean");
        page.edit(0, 0, "java");
        page.addWord(0);
        assert.equal(page.rows(0).length, 2);
        assert.equal(page.input(0, 0).value, "java");
        assert.equal(page.input(0, 1).value, "");
        assert.equal(page.document.activeElement, page.input(0, 1));
        assert.ok(page.groups()[0].children.some(node => node.textContent === "AND"));
        assert.match(page.preview.textContent, /Incomplete or invalid/);
        assert.equal(page.requests.length, 0);
    });
    await test("Add OR Group creates an independent complete group and focuses its first word", async () => {
        const page = client();
        page.mode("boolean");
        page.edit(0, 0, "java");
        page.addGroup();
        assert.equal(page.groups().length, 2);
        assert.equal(page.rows(1).length, 1);
        assert.equal(page.input(0, 0).value, "java");
        assert.equal(page.document.activeElement, page.input(1, 0));
        assert.ok(page.groupList.children.some(node => node.textContent === "OR"));
        assert.equal(page.requests.length, 0);
    });
    await test("Remove Word keeps other conditions and gives the remaining word keyboard focus", async () => {
        const page = client();
        page.mode("boolean");
        page.setGroups([["computer", "java", "python"]]);
        page.removeWord(0, 1);
        assert.equal(page.rows(0).length, 2);
        assert.equal(page.preview.textContent, "computer AND python");
        assert.equal(page.document.activeElement, page.input(0, 1));
        assert.equal(page.rows(0)[1].children[0].htmlFor, page.input(0, 1).id);
        assert.equal(page.rows(0)[1].children[0].textContent, "Group 1, word 2");
        page.removeWord(0, 1);
        assert.equal(page.document.activeElement, page.input(0, 0));
    });
    await test("Remove Group preserves remaining words and renumbers accessible labels", async () => {
        const page = client();
        page.mode("boolean");
        page.setGroups([["java"], ["computer", "python"], ["networking"]]);
        page.removeGroup(0);
        assert.equal(page.groups().length, 2);
        assert.equal(page.preview.textContent, "computer AND python OR networking");
        assert.equal(page.document.activeElement, page.input(0, 0));
        assert.equal(page.rows(0)[0].children[0].textContent, "Group 1, word 1");
        page.removeGroup(1);
        assert.equal(page.document.activeElement, page.input(0, 0));
    });
    await test("Minimum group and word structure cannot be removed", async () => {
        const page = client();
        page.mode("boolean");
        assert.equal(page.groupButton(0, "Remove Group").disabled, true);
        assert.equal(page.rows(0)[0].children[2].disabled, true);
        page.removeWord(0, 0);
        page.removeGroup(0);
        assert.equal(page.groups().length, 1);
        assert.equal(page.rows(0).length, 1);
    });
    for (const [name, groups, expected] of [
        ["One searchable word", [["java"]], "java"],
        ["AND-only", [["java", "programming"]], "java AND programming"],
        ["OR-only", [["java"], ["python"]], "java OR python"],
        ["Expanded two multiword OR groups", [["computer", "java"], ["computer", "python"]],
            "computer AND java OR computer AND python"],
        ["Three mixed groups", [["java", "programming"], ["computer", "networking"], ["python"]],
            "java AND programming OR computer AND networking OR python"],
        ["Case variants, digits and surrounding whitespace", [["  JAVA  ", "25"], ["PyThOn"]], "JAVA AND 25 OR PyThOn"],
        ["Valid unknown term", [["zzzznomatchingterm"]], "zzzznomatchingterm"]
    ]) {
        await test(name + " generates the exact preview and Boolean request without k", async () => {
            const page = client();
            page.limit("5");
            page.mode("boolean");
            page.setGroups(groups);
            assert.equal(page.preview.textContent, expected);
            assert.equal(page.validation.textContent, "");
            const pending = page.submit("ignored raw input");
            const params = new URL(page.requests[0].url, "http://localhost").searchParams;
            assert.deepEqual([...params.keys()], ["q", "mode"]);
            assert.equal(params.get("q"), expected);
            assert.equal(params.get("q"), page.preview.textContent);
            assert.notEqual(params.get("q"), "computer AND java OR python");
            assert.equal(params.get("mode"), "boolean");
            assert.equal(params.has("k"), false);
            assert.equal(page.button.disabled, true);
            page.respond(0, { results: [] });
            await pending;
        });
    }
    for (const [kind, words] of [
        ["empty and whitespace-only", ["", " ", "\t\n"]],
        ["multiple words", ["java python", "java\tpython", "computer\njava"]],
        ["reserved operators and all stop words", ["AND", "or", "NoT", "a", "an", "the", "is", "are", "of", "to", "in", "for", "with", "THE"]],
        ["punctuation, parentheses, quotes and wildcards", ["java,", ".java", "java-python", "java_python", "java.python", "(java)", "[java]", "{java}",
            '"java"', "'java'", "java*", "jav?", "java|python", "java&python", "<script>", "!!!"]],
        ["non-ASCII input", ["\u09ac\u09be\u0982\u09b2\u09be", "caf\u00e9", "\uff12", "\ud83d\ude00"]]
    ]) {
        await test("Advanced Search rejects " + kind + " without a partial preview or request", async () => {
            for (const word of words) {
                const page = client();
                page.mode("boolean");
                page.setGroups([["computer", word], ["python"]]);
                await page.submit();
                assert.equal(page.requests.length, 0, word);
                assert.match(page.preview.textContent, /^Incomplete or invalid/);
                assert.match(page.validation.textContent, /^Group 1, word 2:/);
                assert.equal(page.input(0, 1).attributes["aria-invalid"], "true");
                assert.equal(page.document.activeElement, page.input(0, 1));
                assert.equal(page.button.disabled, false);
                assert.equal(page.list.attributes["aria-busy"], "false");
            }
        });
    }
    await test("An empty OR group is never silently discarded", async () => {
        const page = client();
        page.mode("boolean");
        page.setGroups([["java"], [""]]);
        await page.submit();
        assert.equal(page.requests.length, 0);
        assert.match(page.validation.textContent, /Group 2, word 1/);
        page.edit(1, 0, "python");
        const pending = page.submit();
        assert.equal(new URL(page.requests[0].url, "http://localhost").searchParams.get("q"), "java OR python");
        assert.equal(page.validation.textContent, "");
        assert.equal(page.input(1, 0).attributes["aria-invalid"], "false");
        page.respond(0, { results: [] });
        await pending;
    });
    await test("Editing a completed Advanced Search clears results and updates preview without losing focus", async () => {
        const page = client();
        page.mode("boolean");
        const pending = page.searchGroups([["java"]]);
        page.respond(0, { results: [{ filename: "java.txt" }] });
        await pending;
        const input = page.input(0, 0);
        input.focus();
        page.edit(0, 0, "python");
        assert.equal(page.preview.textContent, "python");
        assert.equal(page.document.activeElement, input);
        assert.deepEqual(page.texts(), []);
        assert.equal(page.status.textContent, "");
    });
    for (const action of ["edit", "addWord", "addGroup", "removeWord", "removeGroup"]) {
        await test(action + " invalidates a pending Advanced Search and ignores its late response", async () => {
            const page = client();
            page.mode("boolean");
            const old = page.searchGroups([["computer", "java"], ["computer", "python"]]);
            if (action === "edit") { page.edit(0, 1, "networking"); }
            else if (action === "removeWord") { page.removeWord(0, 1); }
            else if (action === "removeGroup") { page.removeGroup(1); }
            else if (action === "addWord") { page.addWord(0); }
            else { page.addGroup(); }
            assert.equal(page.requests[0].options.signal.aborted, true);
            assert.deepEqual(page.texts(), []);
            assert.equal(page.button.disabled, false);
            assert.equal(page.list.attributes["aria-busy"], "false");
            page.respond(0, { results: [{ filename: "obsolete.txt" }] });
            await old;
            assert.deepEqual(page.texts(), []);
            assert.equal(page.status.textContent, "");
        });
    }
    await test("An old Advanced Search success cannot unlock a newer pending expression", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.searchGroups([["java"]]);
        page.edit(0, 0, "python");
        const current = page.submit();
        page.respond(0, { results: [{ filename: "old.txt" }] });
        await old;
        assert.equal(page.button.disabled, true);
        assert.equal(page.status.textContent, "Searching...");
        assert.equal(page.list.attributes["aria-busy"], "true");
        page.respond(1, { results: [{ filename: "python.txt" }] });
        await current;
        assert.deepEqual(page.texts(), ["python.txt"]);
    });
    await test("A late Advanced Search error cannot overwrite a newer completed expression", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.searchGroups([["java"]]);
        page.edit(0, 0, "python");
        const current = page.submit();
        page.respond(1, { results: [{ filename: "python.txt" }] });
        await current;
        page.respond(0, { error: "obsolete error" }, false);
        await old;
        assert.deepEqual(page.texts(), ["python.txt"]);
        assert.equal(page.status.textContent, "1 Boolean match(es) found.");
    });
    await test("Editing during Advanced Search JSON parsing invalidates old rendering and cleanup", async () => {
        const page = client();
        page.mode("boolean");
        const old = page.searchGroups([["java"]]);
        let finishJson;
        let started;
        const parsing = new Promise(resolve => { started = resolve; });
        page.requests[0].resolve({ ok: true, json() { return new Promise(resolve => { finishJson = resolve; started(); }); } });
        await parsing;
        page.edit(0, 0, "python");
        const current = page.submit();
        page.respond(1, { results: [{ filename: "python.txt" }] });
        await current;
        finishJson({ results: [{ filename: "stale.txt" }] });
        await old;
        assert.deepEqual(page.texts(), ["python.txt"]);
    });
    await test("Advanced Search preserves words across modes without changing the raw keyword query", async () => {
        const page = client();
        page.query.value = "ordinary multiword query";
        page.mode("boolean");
        page.setGroups([["computer", "java"], ["computer", "python"]]);
        page.mode("keyword");
        assert.equal(page.query.value, "ordinary multiword query");
        page.mode("boolean");
        assert.equal(page.preview.textContent, "computer AND java OR computer AND python");
    });
    await test("Dynamic controls have associated labels, unique IDs, live validation and safe button types", async () => {
        const page = client();
        page.mode("boolean");
        page.setGroups([["computer", "java"], ["computer", "python"]]);
        const ids = new Set();
        for (let g = 0; g < page.groups().length; g++) {
            for (let w = 0; w < page.rows(g).length; w++) {
                const input = page.input(g, w);
                const label = page.rows(g)[w].children[0];
                assert.equal(label.htmlFor, input.id);
                assert.equal(label.textContent, "Group " + (g + 1) + ", word " + (w + 1));
                assert.equal(ids.has(input.id), false);
                ids.add(input.id);
                assert.equal(input.attributes["aria-describedby"], "advanced-help advanced-validation");
            }
            for (const button of [...page.groups()[g].children.filter(node => node.tagName === "button"),
                ...page.rows(g).map(row => row.children[2])]) {
                assert.equal(button.type, "button");
                assert.match(button.attributes["aria-label"], /group \d/);
            }
        }
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.match(html, /<fieldset id="advanced-search" hidden disabled/);
        assert.match(html, /<button type="button" id="add-or-group">Add OR Group<\/button>/);
        assert.match(html, /id="advanced-validation" role="status" aria-live="polite" aria-atomic="true"/);
        assert.doesNotMatch(html, /name="group|name="word/);
        const css = fs.readFileSync("web/styles.css", "utf8");
        assert.match(css, /input:focus-visible/);
        assert.match(css, /button:focus-visible/);
        assert.match(css, /\[hidden\][\s\S]*?display: none !important/);
    });
    await test("Invalid markup-like words never render as HTML in preview or validation", async () => {
        const page = client();
        page.mode("boolean");
        page.edit(0, 0, '<img src=x onerror="alert(1)">');
        await page.submit();
        assert.equal(page.requests.length, 0);
        assert.doesNotMatch(page.preview.textContent + page.validation.textContent, /<img/);
        assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML/);
    });
    await test("Numeric API scores are absent from all rendered result text and attributes", async () => {
        const page = client();
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "z.txt", score: 12345.6789 }, { filename: "a.txt", score: 12.3456 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt", "a.txt"]);
        assert.doesNotMatch(page.list.textContent + page.status.textContent, /Score|12345|12\.34/);
        assert.deepEqual(page.list.children.map(item => item.attributes), [{}, {}]);
    });
    for (const mode of ["keyword", "phrase", "boolean"]) {
        await test(mode + " results expose their own safe Open and Download links in server order", async () => {
            const page = client();
            page.mode(mode);
            if (mode === "boolean") { page.setGroups([["java"]]); }
            const pending = page.submit("java");
            const names = ["z.txt", "a.txt"];
            page.respond(0, { results: names.map(filename => ({ filename, score: 12345.6789 })) });
            await pending;
            assert.deepEqual(page.texts(), names);
            for (let i = 0; i < names.length; i++) {
                const item = page.list.children[i];
                assert.equal(item.children.length, 2);
                const [open, download] = item.children;
                assert.equal(open.tagName, "a");
                assert.equal(download.tagName, "a");
                assert.equal(open.textContent, names[i]);
                assert.equal(download.textContent, "Download");
                assert.equal(open.attributes.href, "/document/view?name=" + names[i]);
                assert.equal(download.attributes.href, "/document/download?name=" + names[i]);
                assert.equal(open.attributes.target, "_blank");
                assert.equal(open.attributes.rel, "noopener noreferrer");
                assert.equal(open.attributes["aria-label"], "Open " + names[i] + " in a new tab");
                assert.equal(download.attributes["aria-label"], "Download " + names[i]);
                assert.equal(download.attributes.download, undefined, "Server attachment header controls downloads");
                assert.equal(open.attributes.tabindex, undefined, "Native href links remain keyboard-focusable");
                assert.equal(item.textContent, names[i] + "Download");
                assert.doesNotMatch(item.textContent, /Score|12345/);
            }
        });
    }
    await test("Document names with spaces, percent, plus, ampersand and Unicode round-trip through URLSearchParams", async () => {
        const page = client();
        const pending = page.submit("java");
        const filename = "notes + 50% & \u09ac.txt";
        page.respond(0, { results: [{ filename, score: 2 }] });
        await pending;
        for (const link of page.list.children[0].children) {
            const url = new URL(link.attributes.href, "http://localhost");
            assert.deepEqual([...url.searchParams.keys()], ["name"]);
            assert.equal(url.searchParams.get("name"), filename);
            assert.match(link.attributes.href, /%2B/);
            assert.match(link.attributes.href, /%25/);
            assert.match(link.attributes.href, /%26/);
            assert.match(link.attributes.href, /%E0%A6%AC/);
        }
    });
    await test("Markup-like filenames stay text and cannot inject links, attributes or executable URLs", async () => {
        const page = client();
        const pending = page.submit("java");
        const filename = 'javascript:<img src=x onerror="alert(1)"> & #?.txt';
        page.respond(0, { results: [{ filename, score: 2 }] });
        await pending;
        const [open, download] = page.list.children[0].children;
        assert.equal(open.textContent, filename);
        assert.equal(open.children.length, 0);
        for (const link of [open, download]) {
            assert.ok(link.attributes.href.startsWith("/document/"));
            assert.equal(new URL(link.attributes.href, "http://localhost").searchParams.get("name"), filename);
            assert.equal(link.attributes.onclick, undefined);
            assert.equal(link.attributes.onerror, undefined);
        }
        assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML/);
    });
    await test("A literal encoded-looking filename is encoded as data rather than interpreted as a path", async () => {
        const page = client();
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "%2e%2e%2fnotes.txt", score: 1 }] });
        await pending;
        for (const link of page.list.children[0].children) {
            assert.match(link.attributes.href, /name=%252e%252e%252fnotes.txt$/);
        }
    });
    await test("Top-K linked results preserve returned order without truncating or showing scores", async () => {
        const page = client();
        page.limit("1");
        const pending = page.submit("java");
        page.respond(0, { results: [{ filename: "z.txt", score: 10 }, { filename: "a.txt", score: 2 }] });
        await pending;
        assert.deepEqual(page.texts(), ["z.txt", "a.txt"]);
        assert.equal(page.list.children.length, 2);
        assert.equal(page.list.textContent, "z.txtDownloada.txtDownload");
    });
    await test("Document links are progressively enhanced while the unchanged native GET still submits raw queries", async () => {
        const html = fs.readFileSync("web/index.html", "utf8");
        assert.match(html, /<form[^>]*action="\/search"[^>]*method="get"/);
        assert.match(html, /<input type="search" id="query" name="q"[^>]*required>/);
        assert.match(html, /<fieldset id="advanced-search" hidden disabled/);
        assert.match(html, /Without JavaScript, enter a manual AND\/OR expression/);
        const css = fs.readFileSync("web/styles.css", "utf8");
        assert.match(css, /li a:focus-visible/);
    });
    console.log("Total passed: " + passed + "; total failed: 0");
}

main().catch(failure => {
    console.error(failure);
    process.exitCode = 1;
});
