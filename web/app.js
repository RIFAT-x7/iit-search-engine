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

function showStatus(message, state) {
    statusMessage.textContent = message;
    statusMessage.setAttribute("data-state", state);
}

async function loadPreview(container, filename, query, mode, requestId, signal) {
    try {
        const response = await fetch("/document/view?" + new URLSearchParams({ name: filename }), { signal });
        if (!response.ok) { throw new Error("Preview unavailable"); }
        const text = await response.text();
        if (requestId !== latestRequestId || signal.aborted) { return; }
        const excerpt = HighlightPresentation.snippet(text, HighlightPresentation.ranges(text, query, mode));
        if (!excerpt) {
            container.textContent = text.length ? "Preview unavailable." : "Empty document.";
            container.setAttribute("data-state", text.length ? "unavailable" : "empty");
            return;
        }
        HighlightPresentation.render(container, excerpt.text, excerpt.ranges, excerpt.leading, excerpt.trailing);
        container.setAttribute("data-state", "ready");
    } catch (error) {
        if (requestId === latestRequestId && !signal.aborted) {
            container.textContent = "Preview unavailable.";
            container.setAttribute("data-state", "unavailable");
        }
    }
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
    button.className = text.startsWith("Remove") ? "button-remove" : "button-secondary";
    button.setAttribute("aria-label", label);
    button.disabled = disabled;
    button.addEventListener("click", action);
    return button;
}

function renderGroups(addedField = null) {
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
        if (addedField && addedField.group === g && addedField.word === 0) {
            group.className += " is-entering";
        }
        const legend = document.createElement("legend");
        legend.textContent = "Group " + (g + 1);
        group.appendChild(legend);
        const heading = document.createElement("div");
        heading.className = "group-heading";
        const explanation = document.createElement("p");
        explanation.textContent = "All words must match";
        heading.appendChild(explanation);
        const removeGroup = actionButton("Remove Group", "Remove group " + (g + 1), groups.length === 1, () => {
            if (groups.length === 1) { return; }
            groups = groups.filter((words, index) => index !== g);
            builderChanged(Math.min(g, groups.length - 1), 0);
        });
        removeGroup.setAttribute("title", groups.length === 1 ? "Keep at least one group." : "Remove group " + (g + 1));
        heading.appendChild(removeGroup);
        group.appendChild(heading);
        const words = document.createElement("div");
        words.className = "advanced-words";
        wordInputs.push([]);
        for (let w = 0; w < groups[g].length; w++) {
            const row = document.createElement("div");
            row.className = "advanced-word";
            if (addedField && addedField.group === g && addedField.word === w && w > 0) {
                row.className += " is-entering";
            }
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
            const removeLabel = "Remove word " + (w + 1) + " from group " + (g + 1);
            const removeWord = actionButton("×", removeLabel,
                groups[g].length === 1, () => {
                    if (groups[g].length === 1) { return; }
                    groups[g] = groups[g].filter((word, index) => index !== w);
                    builderChanged(g, Math.min(w, groups[g].length - 1));
                });
            removeWord.className = "button-remove word-remove";
            removeWord.setAttribute("title", groups[g].length === 1 ? "Keep at least one word in each group." : removeLabel);
            row.appendChild(removeWord);
            if (w > 0) {
                const and = document.createElement("span");
                and.className = "word-operator";
                and.textContent = "AND";
                and.setAttribute("aria-hidden", "true");
                row.appendChild(and);
            }
            words.appendChild(row);
        }
        group.appendChild(words);
        const footer = document.createElement("div");
        footer.className = "group-footer";
        footer.appendChild(actionButton("Add AND Word", "Add AND Word to group " + (g + 1), false, () => {
            groups[g].push("");
            builderChanged(g, groups[g].length - 1, true);
        }));
        group.appendChild(footer);
        groupList.appendChild(group);
    }
    updatePreview();
}

function builderChanged(group, word, addition = false) {
    clearSearch();
    // Only the new field/group enters; existing fields never replay an entrance animation.
    renderGroups(addition ? { group, word } : null);
    wordInputs[group][word].focus();
}

addOrGroup.addEventListener("click", () => {
    groups.push([""]);
    builderChanged(groups.length - 1, 0, true);
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
    showStatus("", "initial");
    resultList.setAttribute("aria-busy", "false");
    submitButton.disabled = false;
    keywordLimit.disabled = selectedMode() !== "keyword";
}

for (const input of modeInputs) {
    input.addEventListener("change", updateMode);
}
keywordLimit.addEventListener("change", clearSearch);
queryInput.addEventListener("input", clearSearch);
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
    // Also abort previews from the preceding completed search.
    clearSearch();
    showStatus("Searching...", "loading");
    resultList.setAttribute("aria-busy", "true");
    submitButton.disabled = true;

    const limit = mode === "keyword" ? keywordLimit.value : "";
    const parameters = new URLSearchParams({ q: query, mode });
    if (limit !== "") {
        parameters.set("k", limit);
    }
    const requestId = ++latestRequestId;
    activeRequest = new AbortController();
    const controller = activeRequest;
    try {
        const response = await fetch("/search?" + parameters.toString(), { signal: controller.signal });
        const data = await response.json();
        if (requestId !== latestRequestId) {
            return;
        }
        if (!response.ok) {
            showStatus(data.error || "Search failed. Please try again.", "error");
            return;
        }

        if (data.results.length === 0) {
            showStatus("No results found.", "empty");
            return;
        }

        for (const result of data.results) {
            const item = document.createElement("li");
            if (typeof result.filename !== "string"
                    || (mode === "keyword" && (typeof result.score !== "number" || !Number.isFinite(result.score)))) {
                throw new Error("Invalid search result.");
            }
            const parameters = new URLSearchParams({ name: result.filename });
            const readerParameters = new URLSearchParams({ name: result.filename, q: query, mode });
            const open = document.createElement("a");
            open.textContent = result.filename;
            open.setAttribute("href", "/reader.html?" + readerParameters.toString());
            open.setAttribute("target", "_blank");
            open.setAttribute("rel", "noopener noreferrer");
            open.setAttribute("aria-label", "Open " + result.filename + " in a new tab");
            const download = document.createElement("a");
            download.textContent = "Download Original";
            download.setAttribute("href", "/document/download?" + parameters.toString());
            download.setAttribute("aria-label", "Download " + result.filename);
            download.className = "document-download";
            item.appendChild(open);
            item.appendChild(download);
            const preview = document.createElement("div");
            preview.className = "result-preview";
            const label = document.createElement("span");
            label.textContent = "Preview: ";
            const excerpt = document.createElement("span");
            excerpt.textContent = "Loading...";
            excerpt.setAttribute("data-state", "loading");
            preview.appendChild(label);
            preview.appendChild(excerpt);
            item.appendChild(preview);
            const type = document.createElement("span");
            type.className = "result-type";
            type.textContent = "Indexed text document";
            item.appendChild(type);
            resultList.appendChild(item);
            void loadPreview(excerpt, result.filename, query, mode, requestId, controller.signal);
        }
        showStatus(limit !== ""
            ? "Showing " + data.results.length + " ranked result(s) (Top " + limit + " limit)."
            : data.results.length + (mode === "keyword" ? " result(s) found."
                : mode === "phrase" ? " phrase match(es) found." : " Boolean match(es) found."), "ready");
    } catch (error) {
        if (requestId === latestRequestId) {
            controller.abort();
            resultList.replaceChildren();
            showStatus("Unable to load search results. Check your connection and try again.", "error");
        }
    } finally {
        if (requestId === latestRequestId) {
            // Keep the controller so later edits/searches can abort any pending previews.
            resultList.setAttribute("aria-busy", "false");
            submitButton.disabled = false;
        }
    }
});
