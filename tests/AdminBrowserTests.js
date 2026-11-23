// Dependency-free DOM/fetch model. Real browser acceptance is reported separately.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync("web/admin.js", "utf8");
const html = fs.readFileSync("web/admin.html", "utf8");
const css = fs.readFileSync("web/admin.css", "utf8");
const TOKEN = "disposable_model_token_" + "x".repeat(32);
let passed = 0;
const tick = () => new Promise(resolve => setImmediate(resolve));
const file = (name = "notice.txt", size = 40) => ({ name, size });

function client() {
    const document = { activeElement: null };
    function element(tag = "div") {
        return {
            tagName: tag, children: [], listeners: {}, attributes: {}, dataset: {}, hidden: false, disabled: false,
            files: [], _value: "", _text: "", parentElement: null,
            get value() { return this._value; },
            set value(value) { this._value = value; if (value === "") { this.files = []; } },
            get textContent() { return this._text + this.children.map(child => child.textContent).join(""); },
            set textContent(value) { this.replaceChildren(); this._text = String(value); },
            replaceChildren() { for (const child of this.children) { child.parentElement = null; } this.children = []; this._text = ""; },
            appendChild(child) { child.parentElement = this; this.children.push(child); return child; },
            setAttribute(name, value) { this.attributes[name] = value; },
            addEventListener(type, listener) { this.listeners[type] = listener; },
            focus() { if (!this.disabled && !this.hidden) { document.activeElement = this; } },
            click() {
                for (let node = this; node; node = node.parentElement) { if (node.hidden || node.disabled) { return; } }
                return this.listeners.click?.();
            },
            set innerHTML(value) { throw new Error("Unsafe rendering"); }
        };
    }
    const elements = {};
    for (const match of html.matchAll(/<([a-z][a-z0-9-]*)\b[^>]*\bid="([^"]+)"[^>]*>/gi)) {
        const node = element(match[1]);
        node.hidden = /\shidden(?:\s|>)/.test(match[0]);
        node.disabled = /\sdisabled(?:\s|>)/.test(match[0]);
        elements[match[2]] = node;
    }
    const get = id => { assert.ok(elements[id], "Unknown HTML id: " + id); return elements[id]; };
    for (const [parent, child] of [["access-panel", "unlock-form"], ["unlock-form", "admin-token"], ["unlock-form", "unlock-admin"],
        ["manager-workspace", "upload-controls"], ["upload-controls", "upload-file"], ["manager-workspace", "document-inventory"],
        ["manager-workspace", "replacement-panel"], ["replacement-panel", "replace-controls"], ["replace-controls", "replacement-file"]]) {
        get(parent).appendChild(get(child));
    }
    document.getElementById = get;
    document.createElement = element;
    const requests = [], confirmations = [], timers = new Map(), events = {};
    let nextTimer = 0, confirmAnswer = true;
    const context = { document, URLSearchParams, AbortController,
        window: { confirm(text) { confirmations.push(text); return confirmAnswer; }, addEventListener(type, listener) { events[type] = listener; } },
        setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url, options) { return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject })); }
    };
    for (const forbidden of ["localStorage", "sessionStorage", "indexedDB", "console"]) {
        Object.defineProperty(context, forbidden, { get() { throw new Error("Forbidden credential sink: " + forbidden); } });
    }
    Object.defineProperty(document, "cookie", { get() { throw new Error("Cookie access"); }, set() { throw new Error("Cookie access"); } });
    vm.runInNewContext(source, context);
    return {
        get, requests, confirmations, timers, events, document,
        submit(id) { return get(id).listeners.submit({ preventDefault() {} }); },
        respond(index, data, status = 200, type = "application/json; charset=utf-8") {
            requests[index].resolve({ status, ok: status >= 200 && status < 300,
                headers: { get() { return type; } }, async json() { return data; } });
        },
        async unlock(names = [], token = TOKEN) {
            get("admin-token").value = token;
            const index = requests.length, pending = this.submit("unlock-form");
            this.respond(index, { documents: names });
            await pending;
        },
        choose(id, selected) { get(id).files = selected ? [selected] : []; get(id).listeners.change(); },
        names() { return get("document-inventory").children.map(row => row.children[0].textContent); },
        button(row, action) { return get("document-inventory").children[row].children[1].children.find(node => node.textContent === action); },
        confirm(answer) { confirmAnswer = answer; },
        timeout() { for (const callback of timers.values()) { callback(); } }
    };
}
async function test(name, check) { await check(); passed++; console.log("PASS: " + name); }
async function completeChange(page, pending, index, method, name, names) {
    page.respond(index, { status: method === "POST" ? "created" : method === "PUT" ? "replaced" : "deleted", filename: name }, method === "POST" ? 201 : 200);
    await tick();
    assert.equal(page.requests[index + 1].options.method, "GET");
    page.respond(index + 1, { documents: names });
    await pending;
}

async function main() {
    await test("Initial state locks management and sends no requests", async () => {
        const page = client();
        assert.equal(page.get("manager-workspace").hidden, true);
        assert.equal(page.get("access-panel").hidden, false);
        assert.equal(page.get("lock-admin").hidden, true);
        assert.equal(page.get("upload-controls").disabled, true);
        assert.equal(page.requests.length, 0);
    });
    await test("Unlock verifies the token before revealing controls and clears the input", async () => {
        const page = client();
        page.get("admin-token").value = TOKEN;
        const pending = page.submit("unlock-form");
        assert.equal(page.get("manager-workspace").hidden, true);
        assert.equal(page.get("admin-token").value, "");
        assert.equal(page.get("unlock-admin").disabled, true);
        page.respond(0, { documents: [] });
        await pending;
        assert.equal(page.get("manager-workspace").hidden, false);
        assert.equal(page.get("upload-controls").disabled, false);
        assert.equal(page.document.activeElement, page.get("refresh-documents"));
    });
    await test("Requests use exact bearer headers and same-origin URLs without credentials in URLs", async () => {
        const page = client(); await page.unlock();
        const request = page.requests[0];
        assert.equal(request.url, "/admin/documents");
        assert.equal(request.options.headers.Authorization, "Bearer " + TOKEN);
        assert.equal(request.options.credentials, "omit");
        assert.equal(request.options.redirect, "error");
        assert.equal(request.options.cache, "no-store");
        assert.ok(!request.url.includes(TOKEN));
    });
    await test("Blank and malformed token formats fail locally without exposing input", async () => {
        for (const token of ["", " ", "short", "x".repeat(32) + "\r\nInjected: value"]) {
            const page = client(); page.get("admin-token").value = token;
            await page.submit("unlock-form");
            assert.equal(page.requests.length, 0);
            assert.equal(page.get("admin-token").value, "");
        }
    });
    await test("Wrong tokens keep controls hidden and do not echo server errors", async () => {
        const page = client(); page.get("admin-token").value = TOKEN;
        const pending = page.submit("unlock-form"); page.respond(0, { error: TOKEN }, 401); await pending;
        assert.equal(page.get("manager-workspace").hidden, true);
        assert.equal(page.get("admin-token").value, "");
        assert.ok(!page.get("admin-status").textContent.includes(TOKEN));
    });
    await test("Unlock network errors clear access state", async () => {
        const page = client(); page.get("admin-token").value = TOKEN;
        const pending = page.submit("unlock-form"); page.requests[0].reject(new Error(TOKEN)); await pending;
        assert.equal(page.get("lock-admin").hidden, true);
        assert.equal(page.get("manager-workspace").hidden, true);
        assert.ok(!page.get("admin-status").textContent.includes(TOKEN));
    });
    await test("No persistent storage or logging receives credentials", async () => {
        const page = client(); await page.unlock(); page.get("lock-admin").click();
        assert.doesNotMatch(source, /localStorage|sessionStorage|indexedDB|document\.cookie|console\.|innerHTML|eval\(/);
        const tokenTag = html.match(/<input[^>]*id="admin-token"[^>]*>/)[0];
        assert.match(tokenTag, /type="password"/); assert.match(tokenTag, /autocomplete="off"/);
        assert.doesNotMatch(tokenTag, /\sname=/);
        assert.doesNotMatch(html, /<script[^>]*>[^<]+/);
    });
    await test("Lock clears inventory, files, target and authorization for subsequent actions", async () => {
        const page = client(); await page.unlock(["a.txt"]);
        page.button(0, "Replace").click(); page.choose("replacement-file", file());
        page.get("lock-admin").click();
        assert.equal(page.get("manager-workspace").hidden, true);
        assert.deepEqual(page.names(), []);
        assert.equal(page.get("replacement-target").textContent, "");
        assert.equal(page.get("replacement-file").files.length, 0);
        await page.get("refresh-documents").click(); await page.submit("upload-form");
        assert.equal(page.requests.length, 1);
    });
    await test("Leaving the page locks access, including a browser back-cache restore", async () => {
        const page = client(); await page.unlock(["a.txt"]); page.events.pagehide();
        assert.equal(page.get("manager-workspace").hidden, true);
        assert.equal(page.get("lock-admin").hidden, true);
    });
    await test("Inventory sorts filenames and exposes only safely encoded text links", async () => {
        const page = client(); await page.unlock(["z.txt", "a & + %.txt"]);
        assert.deepEqual(page.names(), ["a & + %.txt", "z.txt"]);
        const link = page.get("document-inventory").children[0].children[1].children[0];
        assert.equal(new URL(link.href, "http://127.0.0.1:8080").searchParams.get("name"), "a & + %.txt");
        assert.equal(link.rel, "noopener");
        assert.match(page.get("inventory-count").textContent, /^2 managed/);
    });
    await test("Malicious-looking filenames remain text rather than HTML or code", async () => {
        const name = '<img src=x onerror="alert(1)">.txt';
        const page = client(); await page.unlock([name]);
        assert.deepEqual(page.names(), [name]);
        assert.equal(page.get("document-inventory").children[0].children[0].children.length, 0);
    });
    await test("Empty inventory has a clear state and zero count", async () => {
        const page = client(); await page.unlock();
        assert.equal(page.get("inventory-empty").hidden, false);
        assert.match(page.get("inventory-count").textContent, /^0 managed/);
    });
    await test("Upload requires a selected txt file within the byte limit", async () => {
        const page = client(); await page.unlock();
        for (const input of [null, file("x.pdf"), file("x.txt", 1048577)]) {
            page.choose("upload-file", input); await page.submit("upload-form");
            assert.equal(page.requests.length, 1);
            assert.equal(page.get("admin-status").dataset.state, "error");
        }
    });
    await test("File selection reports the exact filename and byte count as text", async () => {
        const page = client(); await page.unlock(); page.choose("upload-file", file("Bangla & notes.TXT", 234));
        assert.equal(page.get("upload-selection").textContent, "Bangla & notes.TXT · 234 bytes");
    });
    await test("Upload passes the original File object with explicit raw UTF-8 content type", async () => {
        const page = client(); await page.unlock(); const selected = file("notice & +.TXT");
        page.choose("upload-file", selected); const pending = page.submit("upload-form");
        assert.equal(page.requests[1].options.body, selected);
        assert.equal(page.requests[1].options.method, "POST");
        assert.equal(page.requests[1].options.headers["Content-Type"], "text/plain; charset=utf-8");
        assert.equal(new URL(page.requests[1].url, "http://localhost").searchParams.get("name"), selected.name);
        await completeChange(page, pending, 1, "POST", selected.name, [selected.name]);
        assert.match(page.get("admin-status").textContent, /now searchable/);
        assert.equal(page.get("upload-file").files.length, 0);
    });
    await test("Empty files and the inclusive 1 MiB client boundary are accepted", async () => {
        for (const size of [0, 1048576]) {
            const page = client(); await page.unlock(); page.choose("upload-file", file("a.txt", size));
            const pending = page.submit("upload-form");
            await completeChange(page, pending, 1, "POST", "a.txt", ["a.txt"]);
        }
    });
    await test("Busy state prevents duplicate upload, refresh and conflicting row actions", async () => {
        const page = client(); await page.unlock(["a.txt"]); page.choose("upload-file", file());
        const pending = page.submit("upload-form");
        assert.equal(page.get("manager-workspace").attributes["aria-busy"], "true");
        assert.equal(page.get("upload-controls").disabled, true);
        assert.equal(page.get("refresh-documents").disabled, true);
        assert.equal(page.get("lock-admin").disabled, false);
        await page.submit("upload-form"); page.button(0, "Delete").click(); await page.get("refresh-documents").click();
        assert.equal(page.requests.length, 2);
        await completeChange(page, pending, 1, "POST", "notice.txt", ["a.txt", "notice.txt"]);
        assert.equal(page.get("manager-workspace").attributes["aria-busy"], "false");
        assert.equal(page.timers.size, 0);
    });
    await test("A conflict requires a confirmed inventory refresh before another mutation", async () => {
        const page = client(); await page.unlock(["notice.txt"]); page.choose("upload-file", file());
        const pending = page.submit("upload-form"); page.respond(1, { error: "private path" }, 409); await pending;
        assert.match(page.get("admin-status").textContent, /conflicts/);
        assert.equal(page.get("upload-controls").disabled, true);
        const refreshed = page.get("refresh-documents").click(); page.respond(2, { documents: ["notice.txt"] }); await refreshed;
        assert.equal(page.get("upload-controls").disabled, false);
    });
    await test("Known validation rejections show safe specific feedback without retry", async () => {
        for (const code of [400, 404, 413, 415]) {
            const page = client(); await page.unlock(); page.choose("upload-file", file());
            const pending = page.submit("upload-form"); page.respond(1, { error: TOKEN + " stack trace" }, code); await pending;
            assert.equal(page.requests.length, 2);
            assert.equal(page.get("admin-status").dataset.state, "error");
            assert.ok(!page.get("admin-status").textContent.includes(TOKEN));
        }
    });
    await test("Replacement uses the selected existing target and requires an explicit file", async () => {
        const page = client(); await page.unlock(["existing.txt"]); page.button(0, "Replace").click();
        assert.equal(page.get("replacement-target").textContent, "existing.txt");
        assert.equal(page.get("replacement-panel").hidden, false);
        await page.submit("replace-form"); assert.equal(page.requests.length, 1);
        page.choose("replacement-file", file("new-local.txt")); page.confirm(false);
        await page.submit("replace-form"); assert.equal(page.requests.length, 1);
        assert.match(page.confirmations[0], /existing\.txt/); assert.match(page.confirmations[0], /new-local\.txt/);
    });
    await test("Confirmed replacement retains managed identity and refreshes inventory", async () => {
        const page = client(); await page.unlock(["existing.txt"]); page.button(0, "Replace").click();
        const selected = file("different.txt"); page.choose("replacement-file", selected);
        const pending = page.submit("replace-form"); const request = page.requests[1];
        assert.equal(request.options.method, "PUT"); assert.equal(request.options.body, selected);
        assert.equal(new URL(request.url, "http://localhost").searchParams.get("name"), "existing.txt");
        await completeChange(page, pending, 1, "PUT", "existing.txt", ["existing.txt"]);
        assert.match(page.get("admin-status").textContent, /replacement contents/);
        assert.equal(page.get("replacement-panel").hidden, true);
    });
    await test("Cancel replacement clears its file and target", async () => {
        const page = client(); await page.unlock(["a.txt"]); page.button(0, "Replace").click();
        page.choose("replacement-file", file()); page.get("cancel-replacement").click();
        assert.equal(page.get("replacement-panel").hidden, true);
        assert.equal(page.get("replacement-target").textContent, "");
        assert.equal(page.get("replacement-file").files.length, 0);
    });
    await test("Delete requires explicit confirmation and cancellation sends nothing", async () => {
        const page = client(); await page.unlock(["a & b.txt"]); page.confirm(false);
        await page.button(0, "Delete").click(); assert.equal(page.requests.length, 1);
        assert.match(page.confirmations[0], /a & b\.txt/);
    });
    await test("Confirmed deletion sends no body and removes the visible document", async () => {
        const page = client(); await page.unlock(["a.txt"]); const pending = page.button(0, "Delete").click();
        assert.equal(page.requests[1].options.method, "DELETE");
        assert.equal(page.requests[1].options.body, undefined);
        await completeChange(page, pending, 1, "DELETE", "a.txt", []);
        assert.deepEqual(page.names(), []); assert.match(page.get("admin-status").textContent, /no longer searchable/);
    });
    await test("Refresh renders only the latest confirmed inventory", async () => {
        const page = client(); await page.unlock(["old.txt"]); const pending = page.get("refresh-documents").click();
        page.respond(1, { documents: ["new.txt"] }); await pending;
        assert.deepEqual(page.names(), ["new.txt"]);
    });
    await test("Failed refresh retains labelled old inventory but disables mutations", async () => {
        const page = client(); await page.unlock(["a.txt"]); const pending = page.get("refresh-documents").click();
        page.requests[1].reject(new Error("offline")); await pending;
        assert.deepEqual(page.names(), ["a.txt"]); assert.equal(page.get("upload-controls").disabled, true);
        assert.match(page.get("admin-status").textContent, /last confirmed refresh/);
    });
    await test("Network loss after a mutation never retries or claims rollback", async () => {
        const page = client(); await page.unlock(); page.choose("upload-file", file());
        const pending = page.submit("upload-form"); page.requests[1].reject(new Error(TOKEN)); await pending;
        assert.equal(page.requests.length, 2);
        assert.match(page.get("admin-status").textContent, /completion is uncertain/);
        assert.equal(page.get("recovery-panel").hidden, false);
        assert.equal(page.get("upload-controls").disabled, true);
        assert.ok(!page.get("admin-status").textContent.includes(TOKEN));
    });
    await test("Uncertain replacement requires refresh and a human content check", async () => {
        const page = client(); await page.unlock(["a.txt"]); page.button(0, "Replace").click(); page.choose("replacement-file", file());
        const pending = page.submit("replace-form"); page.requests[1].reject(new Error("lost response")); await pending;
        page.get("acknowledge-recovery").click(); assert.equal(page.get("upload-controls").disabled, true);
        const refreshed = page.get("refresh-documents").click(); page.respond(2, { documents: ["a.txt"] }); await refreshed;
        assert.equal(page.get("upload-controls").disabled, true);
        assert.match(page.get("admin-status").textContent, /Check document contents/);
        page.get("acknowledge-recovery").click(); assert.equal(page.get("upload-controls").disabled, false);
        assert.match(page.get("admin-status").textContent, /No success or rollback was inferred/);
        assert.match(html, /Filenames alone cannot confirm replacement content/);
    });
    await test("Storage errors and overload responses are treated as uncertain writes", async () => {
        for (const code of [500, 503, 429]) {
            const page = client(); await page.unlock(); page.choose("upload-file", file());
            const pending = page.submit("upload-form"); page.respond(1, {}, code); await pending;
            assert.equal(page.get("recovery-panel").hidden, false); assert.equal(page.requests.length, 2);
        }
    });
    await test("Unexpected successful status, JSON shape and content type cannot claim success", async () => {
        for (const [data, code, type] of [[{}, 201, "application/json"], [{ status: "created", filename: "wrong.txt" }, 201, "application/json"],
            [{ status: "created", filename: "notice.txt" }, 200, "application/json"], [null, 201, "text/html"]]) {
            const page = client(); await page.unlock(); page.choose("upload-file", file());
            const pending = page.submit("upload-form"); page.respond(1, data, code, type); await pending;
            assert.equal(page.get("recovery-panel").hidden, false);
            assert.equal(page.requests.length, 2);
        }
    });
    await test("Malformed JSON after a possibly completed write remains uncertain", async () => {
        const page = client(); await page.unlock(); page.choose("upload-file", file()); const pending = page.submit("upload-form");
        page.requests[1].resolve({ status: 201, ok: true, headers: { get: () => "application/json" }, json: async () => { throw new SyntaxError("bad JSON"); } });
        await pending; assert.equal(page.get("recovery-panel").hidden, false);
    });
    await test("Confirmed mutation followed by refresh failure keeps its confirmed outcome", async () => {
        const page = client(); await page.unlock(); page.choose("upload-file", file()); const pending = page.submit("upload-form");
        page.respond(1, { status: "created", filename: "notice.txt" }, 201); await tick();
        page.requests[2].reject(new Error("offline")); await pending;
        assert.match(page.get("admin-status").textContent, /Document uploaded/);
        assert.match(page.get("admin-status").textContent, /Inventory could not be refreshed/);
        assert.equal(page.get("recovery-panel").hidden, true);
    });
    await test("Authorization rejection on any operation locks without claiming an uncertain write", async () => {
        for (const code of [401, 403]) {
            const page = client(); await page.unlock(); page.choose("upload-file", file()); const pending = page.submit("upload-form");
            page.respond(1, { error: TOKEN }, code); await pending;
            assert.equal(page.get("manager-workspace").hidden, true);
            await page.unlock(); assert.equal(page.get("recovery-panel").hidden, true);
        }
    });
    await test("Invalid inventory payloads fail closed rather than rendering partial data", async () => {
        for (const data of [{}, { documents: ["a.txt", 4] }, { documents: ["a.txt", "a.txt"] }, { documents: [""] }]) {
            const page = client(); page.get("admin-token").value = TOKEN; const pending = page.submit("unlock-form");
            page.respond(0, data); await pending;
            assert.equal(page.get("manager-workspace").hidden, true);
        }
    });
    await test("Old refresh responses cannot overwrite a newly unlocked session", async () => {
        const page = client(); await page.unlock(["old.txt"]); const pending = page.get("refresh-documents").click();
        page.get("lock-admin").click(); await page.unlock(["fresh.txt"]);
        page.respond(1, { documents: ["stale.txt"] }); await pending;
        assert.deepEqual(page.names(), ["fresh.txt"]);
    });
    await test("Cancelled unlock responses cannot reveal management controls", async () => {
        const page = client(); page.get("admin-token").value = TOKEN; const pending = page.submit("unlock-form");
        page.get("lock-admin").click(); page.respond(0, { documents: ["secret.txt"] }); await pending;
        assert.equal(page.get("manager-workspace").hidden, true); assert.deepEqual(page.names(), []);
        assert.equal(page.requests[0].options.signal.aborted, true);
    });
    await test("Locking a pending write records uncertainty and ignores its late success", async () => {
        const page = client(); await page.unlock(); page.choose("upload-file", file()); const pending = page.submit("upload-form");
        page.get("lock-admin").click(); page.respond(1, { status: "created", filename: "notice.txt" }, 201); await pending;
        assert.equal(page.requests.length, 2); assert.equal(page.get("manager-workspace").hidden, true);
        await page.unlock(["notice.txt"]); assert.equal(page.get("recovery-panel").hidden, false);
    });
    await test("Timeout aborts the request and exposes uncertainty without retry", async () => {
        const page = client(); await page.unlock(); page.choose("upload-file", file()); const pending = page.submit("upload-form");
        page.timeout(); assert.equal(page.requests[1].options.signal.aborted, true);
        page.requests[1].reject(new Error("AbortError")); await pending;
        assert.equal(page.get("recovery-panel").hidden, false); assert.equal(page.requests.length, 2);
    });
    await test("Manager markup and styles provide keyboard, mobile and safe form boundaries", async () => {
        assert.match(html, /class="skip-link"/); assert.match(html, /aria-live="polite"/);
        assert.match(html, /id="replacement-heading" tabindex="-1"/);
        assert.match(html, /href="\/">← Back to Search/);
        assert.match(html, /type="file" accept="\.txt,text\/plain"/);
        assert.match(css, /@media \(max-width: 850px\)/); assert.match(css, /@media \(max-width: 620px\)/);
        assert.match(html, /href="\/styles.css"/); assert.match(html, /src="\/admin.js" defer/);
    });
    console.log("Admin browser model: total passed: " + passed + "; total failed: 0");
}
main().catch(error => { console.error(error); process.exitCode = 1; });
