const form = document.getElementById("search-form");
const queryInput = document.getElementById("query");
const submitButton = form.querySelector('button[type="submit"]');
const statusMessage = document.getElementById("search-status");
const resultList = document.getElementById("result-list");
const modeInputs = form.querySelectorAll('input[name="mode"]');
const keywordLimit = document.getElementById("keyword-limit");
const rawQuery = document.getElementById("raw-query");
const advancedSearch = document.getElementById("advanced-search");
const groupList = document.getElementById("advanced-groups");
const addOrGroup = document.getElementById("add-or-group");
const expressionPreview = document.getElementById("advanced-preview");
const validationMessage = document.getElementById("advanced-validation");
// Words within a group use AND; complete groups use OR. Keep raw input while editing.
let groups = [[""]];
let wordInputs = [];
// Same twelve stop words as TextProcessor; NOT is also reserved by the Boolean parser.
const reservedWords = ["a", "an", "the", "and", "or", "is", "are", "of", "to", "in", "for", "with", "not"];
let latestRequestId = 0;
let activeRequest = null;

function selectedMode() {
    return form.querySelector('input[name="mode"]:checked').value;
}

function updatePreview() {
    const expressions = [];
    let firstInvalid = null;
    let message = "";
    for (let g = 0; g < groups.length; g++) {
        const words = [];
        for (let w = 0; w < groups[g].length; w++) {
            const word = groups[g][w].trim();
            let reason = "";
            if (word === "") {
                reason = "Enter a searchable word.";
            } else if (!/^[A-Za-z0-9]+$/.test(word)) {
                // Deliberately stricter than raw API punctuation normalization: never alter an operand.
                reason = "Use one word of ASCII letters or digits, without spaces or punctuation.";
            } else if (reservedWords.includes(word.toLowerCase())) {
                reason = "Stop words and AND, OR, NOT cannot be search words.";
            }
            wordInputs[g][w].setAttribute("aria-invalid", reason ? "true" : "false");
            if (reason && !firstInvalid) {
                firstInvalid = wordInputs[g][w];
                message = "Group " + (g + 1) + ", word " + (w + 1) + ": " + reason;
            }
            words.push(word);
        }
        expressions.push(words.join(" AND "));
    }
    const query = firstInvalid ? "" : expressions.join(" OR ");
    expressionPreview.textContent = firstInvalid ? "Incomplete or invalid expression. Check the word fields." : query;
    validationMessage.textContent = message;
    return { query, firstInvalid };
}

function actionButton(text, label, disabled, action) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = text;
    button.setAttribute("aria-label", label);
    button.disabled = disabled;
    button.addEventListener("click", action);
    return button;
}

function renderGroups() {
    groupList.replaceChildren();
    wordInputs = [];
    for (let g = 0; g < groups.length; g++) {
        if (g > 0) {
            const or = document.createElement("p");
            or.className = "logical-operator";
            or.textContent = "OR";
            groupList.appendChild(or);
        }
        const group = document.createElement("fieldset");
        group.className = "advanced-group";
        const legend = document.createElement("legend");
        legend.textContent = "Group " + (g + 1) + " (all words must match)";
        group.appendChild(legend);
        wordInputs.push([]);
        for (let w = 0; w < groups[g].length; w++) {
            if (w > 0) {
                const and = document.createElement("p");
                and.className = "logical-operator";
                and.textContent = "AND";
                group.appendChild(and);
            }
            const row = document.createElement("div");
            row.className = "advanced-word";
            const label = document.createElement("label");
            const input = document.createElement("input");
            input.type = "text";
            input.id = "group-" + (g + 1) + "-word-" + (w + 1);
            input.value = groups[g][w];
            input.setAttribute("aria-describedby", "advanced-help advanced-validation");
            label.htmlFor = input.id;
            label.textContent = "Group " + (g + 1) + ", word " + (w + 1);
            input.addEventListener("input", () => {
                groups[g][w] = input.value;
                clearSearch();
                updatePreview();
            });
            row.appendChild(label);
            row.appendChild(input);
            wordInputs[g].push(input);
            row.appendChild(actionButton("Remove Word", "Remove word " + (w + 1) + " from group " + (g + 1),
                groups[g].length === 1, () => {
                    if (groups[g].length === 1) { return; }
                    groups[g] = groups[g].filter((word, index) => index !== w);
                    builderChanged(g, Math.min(w, groups[g].length - 1));
                }));
            group.appendChild(row);
        }
        group.appendChild(actionButton("Add AND Word", "Add AND Word to group " + (g + 1), false, () => {
            groups[g].push("");
            builderChanged(g, groups[g].length - 1);
        }));
        group.appendChild(actionButton("Remove Group", "Remove group " + (g + 1), groups.length === 1, () => {
            if (groups.length === 1) { return; }
            groups = groups.filter((words, index) => index !== g);
            builderChanged(Math.min(g, groups.length - 1), 0);
        }));
        groupList.appendChild(group);
    }
    updatePreview();
}

function builderChanged(group, word) {
    clearSearch();
    renderGroups();
    wordInputs[group][word].focus();
}

addOrGroup.addEventListener("click", () => {
    groups.push([""]);
    builderChanged(groups.length - 1, 0);
});

function updateMode() {
    clearSearch();
    const advanced = selectedMode() === "boolean";
    rawQuery.hidden = advanced;
    queryInput.disabled = advanced;
    advancedSearch.hidden = !advanced;
    advancedSearch.disabled = !advanced;
    if (advanced) { updatePreview(); }
}

function clearSearch() {
    // Ignore even a late response whose cancellation has not completed yet.
    latestRequestId++;
    if (activeRequest) {
        activeRequest.abort();
        activeRequest = null;
    }
    resultList.replaceChildren();
    statusMessage.textContent = "";
    resultList.setAttribute("aria-busy", "false");
    submitButton.disabled = false;
    keywordLimit.disabled = selectedMode() !== "keyword";
}

for (const input of modeInputs) {
    input.addEventListener("change", updateMode);
}
keywordLimit.addEventListener("change", clearSearch);
renderGroups();
updateMode();

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitButton.disabled) {
        return;
    }

    const mode = selectedMode();
    let query = queryInput.value;
    if (mode === "boolean") {
        const expression = updatePreview();
        if (expression.firstInvalid) {
            expression.firstInvalid.focus();
            return;
        }
        query = expression.query;
    }
    resultList.replaceChildren();
    statusMessage.textContent = "Searching...";
    resultList.setAttribute("aria-busy", "true");
    submitButton.disabled = true;

    const limit = mode === "keyword" ? keywordLimit.value : "";
    const parameters = new URLSearchParams({ q: query, mode });
    if (limit !== "") {
        parameters.set("k", limit);
    }
    const requestId = ++latestRequestId;
    activeRequest = new AbortController();
    try {
        const response = await fetch("/search?" + parameters.toString(), { signal: activeRequest.signal });
        const data = await response.json();
        if (requestId !== latestRequestId) {
            return;
        }
        if (!response.ok) {
            statusMessage.textContent = data.error || "Search failed. Please try again.";
            return;
        }

        if (data.results.length === 0) {
            statusMessage.textContent = "No results found.";
            return;
        }

        for (const result of data.results) {
            const item = document.createElement("li");
            if (typeof result.filename !== "string"
                    || (mode === "keyword" && (typeof result.score !== "number" || !Number.isFinite(result.score)))) {
                throw new Error("Invalid search result.");
            }
            const parameters = new URLSearchParams({ name: result.filename });
            const open = document.createElement("a");
            open.textContent = result.filename;
            open.setAttribute("href", "/document/view?" + parameters.toString());
            open.setAttribute("target", "_blank");
            open.setAttribute("rel", "noopener noreferrer");
            open.setAttribute("aria-label", "Open " + result.filename + " in a new tab");
            const download = document.createElement("a");
            download.textContent = "Download";
            download.setAttribute("href", "/document/download?" + parameters.toString());
            download.setAttribute("aria-label", "Download " + result.filename);
            download.className = "document-download";
            item.appendChild(open);
            item.appendChild(download);
            resultList.appendChild(item);
        }
        statusMessage.textContent = limit !== ""
            ? "Showing " + data.results.length + " ranked result(s) (Top " + limit + " limit)."
            : data.results.length + (mode === "keyword" ? " result(s) found."
                : mode === "phrase" ? " phrase match(es) found." : " Boolean match(es) found.");
    } catch (error) {
        if (requestId === latestRequestId) {
            resultList.replaceChildren();
            statusMessage.textContent = "Unable to load search results. Check your connection and try again.";
        }
    } finally {
        if (requestId === latestRequestId) {
            activeRequest = null;
            resultList.setAttribute("aria-busy", "false");
            submitButton.disabled = false;
        }
    }
});
