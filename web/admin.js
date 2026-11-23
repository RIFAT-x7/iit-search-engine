/* Same-origin raw-file administration. Credentials live only inside this closure. */
(() => {
    "use strict";
    const byId = id => document.getElementById(id);
    const access = byId("access-panel"), workspace = byId("manager-workspace");
    const tokenInput = byId("admin-token"), unlockButton = byId("unlock-admin"), lockButton = byId("lock-admin");
    const status = byId("admin-status"), inventory = byId("document-inventory"), count = byId("inventory-count");
    const uploadFile = byId("upload-file"), replacementFile = byId("replacement-file");
    const uploadControls = byId("upload-controls"), replaceControls = byId("replace-controls");
    const refreshButton = byId("refresh-documents"), recovery = byId("recovery-panel"), acknowledge = byId("acknowledge-recovery");
    const replacementPanel = byId("replacement-panel"), targetLabel = byId("replacement-target");
    const MAX_BYTES = 1024 * 1024;
    let token = null, unlocked = false, busy = false, generation = 0, controller = null;
    let names = [], inventoryReady = false, target = null, uncertain = false, pendingMutation = false;
    let actionButtons = [];

    function message(text, state = "initial") {
        status.textContent = text;
        status.dataset.state = state;
    }
    function controls() {
        access.hidden = unlocked;
        workspace.hidden = !unlocked;
        workspace.setAttribute("aria-busy", String(busy));
        tokenInput.disabled = busy;
        unlockButton.disabled = busy;
        lockButton.hidden = token === null;
        lockButton.textContent = unlocked ? "Lock administration" : "Cancel unlock";
        refreshButton.disabled = busy || !unlocked;
        const blocked = busy || !unlocked || !inventoryReady || uncertain;
        uploadControls.disabled = blocked;
        replaceControls.disabled = blocked;
        for (const button of actionButtons) { button.disabled = blocked; }
        recovery.hidden = !uncertain;
        acknowledge.disabled = busy || !unlocked || !inventoryReady;
    }
    function clearFiles() {
        uploadFile.value = "";
        replacementFile.value = "";
        byId("upload-selection").textContent = "No file selected.";
        byId("replacement-selection").textContent = "No file selected.";
    }
    function cancelReplacement() {
        target = null;
        targetLabel.textContent = "";
        replacementFile.value = "";
        byId("replacement-selection").textContent = "No file selected.";
        replacementPanel.hidden = true;
    }
    function lock(text = "Administration locked. Enter the token to unlock again.", focus = true) {
        if (pendingMutation) { uncertain = true; }
        generation++;
        if (controller) { controller.abort(); }
        controller = null;
        token = null;
        tokenInput.value = "";
        unlocked = false;
        busy = false;
        pendingMutation = false;
        names = [];
        actionButtons = [];
        inventoryReady = false;
        inventory.replaceChildren();
        count.textContent = "Inventory has not been loaded.";
        clearFiles();
        cancelReplacement();
        controls();
        message(uncertain ? text + " The last change needs checking after you unlock." : text);
        if (focus) { tokenInput.focus(); }
    }
    function begin(text) {
        busy = true;
        controller = new AbortController();
        const id = ++generation;
        const timer = setTimeout(() => { if (id === generation) { controller.abort(); } }, 45000);
        controls();
        message(text, "loading");
        return { id, timer };
    }
    function finish(operation) {
        clearTimeout(operation.timer);
        if (operation.id !== generation) { return; }
        busy = false;
        controller = null;
        pendingMutation = false;
        controls();
    }
    class HttpFailure extends Error { constructor(code) { super("Request rejected"); this.code = code; } }
    async function send(method, filename, file) {
        const url = "/admin/documents" + (filename === undefined ? "" : "?" + new URLSearchParams({ name: filename }));
        const options = { method, headers: { Authorization: "Bearer " + token }, signal: controller.signal,
            cache: "no-store", credentials: "omit", redirect: "error" };
        if (method === "POST" || method === "PUT") {
            options.headers["Content-Type"] = "text/plain; charset=utf-8";
            options.body = file; // File/Blob bytes go directly to fetch, without text conversion.
        }
        const response = await fetch(url, options);
        if (!response.ok) { throw new HttpFailure(response.status); }
        if (response.status !== (method === "POST" ? 201 : 200)
                || !response.headers.get("Content-Type")?.toLowerCase().startsWith("application/json")) {
            throw new Error("Unexpected response");
        }
        const data = await response.json();
        if (method === "GET") {
            if (!data || !Array.isArray(data.documents) || data.documents.some(name => typeof name !== "string" || !name.trim())
                    || new Set(data.documents).size !== data.documents.length) { throw new Error("Invalid inventory"); }
        } else {
            const expected = method === "POST" ? "created" : method === "PUT" ? "replaced" : "deleted";
            if (!data || data.status !== expected || data.filename !== filename) { throw new Error("Unconfirmed operation"); }
        }
        return data;
    }
    function render(data) {
        names = data.documents.slice().sort();
        inventoryReady = true;
        inventory.replaceChildren();
        actionButtons = [];
        count.textContent = names.length + " managed document" + (names.length === 1 ? "" : "s") + ". Sorted by filename.";
        byId("inventory-empty").hidden = names.length !== 0;
        for (const name of names) {
            const item = document.createElement("li"), label = document.createElement("strong");
            label.className = "inventory-name";
            label.textContent = name;
            const actions = document.createElement("div");
            actions.className = "inventory-actions";
            const view = document.createElement("a");
            view.textContent = "View text";
            view.href = "/document/view?" + new URLSearchParams({ name });
            view.target = "_blank";
            view.rel = "noopener";
            view.setAttribute("aria-label", "View text of " + name + " (new tab)");
            actions.appendChild(view);
            for (const [text, handler] of [["Replace", () => selectReplacement(name)], ["Delete", () => remove(name)]]) {
                const button = document.createElement("button");
                button.type = "button";
                button.textContent = text;
                if (text === "Delete") { button.className = "delete-document"; }
                button.setAttribute("aria-label", text + " " + name);
                button.addEventListener("click", handler);
                actionButtons.push(button);
                actions.appendChild(button);
            }
            item.appendChild(label);
            item.appendChild(actions);
            inventory.appendChild(item);
        }
        cancelReplacement();
        controls();
    }
    function failure(error, mutation = false, confirmed = "") {
        if (error instanceof HttpFailure && (error.code === 401 || error.code === 403)) {
            pendingMutation = false; // An authoritative authorization rejection performed no mutation.
            lock(confirmed ? confirmed + " Authorization was rejected; unlock again."
                : "Authorization was rejected. Check the token and local application address, then unlock again.");
            return;
        }
        if (confirmed) {
            inventoryReady = false;
            message(confirmed + " Inventory could not be refreshed. Use Refresh inventory before another change.", "warning");
            return;
        }
        const rejected = error instanceof HttpFailure && [400, 404, 409, 413, 415].includes(error.code);
        if (mutation && !rejected) {
            uncertain = true;
            inventoryReady = false;
            message("The change's completion is uncertain. It was not retried. Refresh the inventory and check the affected document before another change.", "warning");
        } else if (mutation) {
            const explanations = { 400: "The server rejected the filename or text. Use valid UTF-8 .txt content without NUL characters.",
                404: "The target document no longer exists. Refresh the inventory.",
                409: "That filename conflicts with an existing document. Refresh the inventory and choose another filename.",
                413: "The file exceeds the server's 1 MiB limit.", 415: "The server requires raw UTF-8 plain text." };
            if (error.code === 404 || error.code === 409) { inventoryReady = false; }
            message(explanations[error.code], "error");
        } else {
            inventoryReady = false;
            message("Inventory could not be loaded. Displayed names, if any, are from the last confirmed refresh. Try Refresh inventory.", "warning");
        }
    }
    async function refresh() {
        if (busy || !unlocked) { return; }
        const operation = begin("Refreshing the managed collection…");
        try {
            const data = await send("GET");
            if (operation.id !== generation) { return; }
            render(data);
            message(uncertain ? "Inventory refreshed. Check document contents before acknowledging the uncertain change." : "Inventory refreshed.", uncertain ? "warning" : "success");
        } catch (error) { if (operation.id === generation) { failure(error); } }
        finally { finish(operation); }
    }
    function validFile(file) {
        if (!file) { message("Choose one .txt file first.", "error"); return false; }
        if (!/\.txt$/i.test(file.name)) { message("Choose a .txt file. Other document formats are not supported.", "error"); return false; }
        if (file.size > MAX_BYTES) { message("Choose a file no larger than 1 MiB (1,048,576 bytes).", "error"); return false; }
        return true;
    }
    function canChange() { return unlocked && !busy && inventoryReady && !uncertain; }
    async function mutate(method, name, file) {
        if (!canChange()) { return; }
        const operation = begin("Updating the collection and search index…");
        pendingMutation = true;
        let confirmed = "";
        try {
            await send(method, name, file);
            if (operation.id !== generation) { return; }
            pendingMutation = false;
            confirmed = method === "POST" ? "Document uploaded. It is now searchable."
                : method === "PUT" ? "Document replaced. Search now reflects the replacement contents."
                    : "Document deleted. It is no longer searchable.";
            clearFiles();
            cancelReplacement();
            inventoryReady = false;
            const data = await send("GET");
            if (operation.id !== generation) { return; }
            render(data);
            message(confirmed, "success");
        } catch (error) { if (operation.id === generation) { failure(error, true, confirmed); } }
        finally { finish(operation); }
    }
    function selectReplacement(name) {
        if (!canChange() || !names.includes(name)) { return; }
        cancelReplacement();
        target = name;
        targetLabel.textContent = name;
        replacementPanel.hidden = false;
        byId("replacement-heading").focus();
    }
    async function remove(name) {
        if (!canChange() || !names.includes(name)) { return; }
        if (!window.confirm('Delete "' + name + '"? It will be removed from the collection and search results.')) { return; }
        await mutate("DELETE", name);
    }
    byId("unlock-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (busy || unlocked) { return; }
        if (!/^[A-Za-z0-9_-]{32,256}$/.test(tokenInput.value)) {
            tokenInput.value = "";
            message("Enter the configured 32–256 character administrator token using letters, digits, underscores or hyphens.", "error");
            return;
        }
        token = tokenInput.value;
        tokenInput.value = "";
        const operation = begin("Verifying administrator access…");
        try {
            const data = await send("GET");
            if (operation.id !== generation) { return; }
            unlocked = true;
            render(data);
            message(uncertain ? "Unlocked. Check the interrupted change before submitting another." : "Unlocked. Your managed collection is ready.", uncertain ? "warning" : "success");
        } catch (error) {
            if (operation.id === generation) { lock("Unable to unlock. Check the token, server and exact local application address, then try again."); }
        } finally {
            finish(operation);
            if (operation.id === generation && unlocked) { refreshButton.focus(); }
        }
    });
    byId("upload-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (!canChange()) { return; }
        const file = uploadFile.files[0];
        if (validFile(file)) { await mutate("POST", file.name, file); }
    });
    byId("replace-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (!canChange() || !target || !names.includes(target)) { return; }
        const file = replacementFile.files[0];
        if (!validFile(file)) { return; }
        if (window.confirm('Replace the contents of "' + target + '" using "' + file.name + '"? The managed filename stays the same.')) {
            await mutate("PUT", target, file);
        }
    });
    for (const [input, label] of [[uploadFile, "upload-selection"], [replacementFile, "replacement-selection"]]) {
        input.addEventListener("change", () => {
            const file = input.files[0];
            byId(label).textContent = file ? file.name + " · " + file.size.toLocaleString() + " bytes" : "No file selected.";
        });
    }
    refreshButton.addEventListener("click", refresh);
    lockButton.addEventListener("click", () => lock());
    byId("cancel-replacement").addEventListener("click", () => { if (!busy) { cancelReplacement(); refreshButton.focus(); } });
    acknowledge.addEventListener("click", () => {
        if (busy || !inventoryReady || !unlocked) { return; }
        uncertain = false;
        controls();
        message("Collection check acknowledged. No success or rollback was inferred for the earlier request.");
        refreshButton.focus();
    });
    window.addEventListener("pagehide", () => lock("Administration locked after leaving the page.", false));
    lock("Unlock to manage this server's document collection.", false);
})();
