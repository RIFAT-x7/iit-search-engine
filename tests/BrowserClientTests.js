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
    const radios = [Object.assign(element(), { value: "keyword", checked: true }),
        Object.assign(element(), { value: "phrase", checked: false })];
    const requests = [];
    form.querySelector = (selector) => selector.startsWith("button") ? button : radios.find(radio => radio.checked);
    form.querySelectorAll = () => radios;
    const elements = { "search-form": form, query, "search-status": status, "result-list": list };
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
        query, button, status, list, requests,
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
    console.log("Total passed: " + passed + "; total failed: 0");
}

main().catch(failure => {
    console.error(failure);
    process.exitCode = 1;
});
