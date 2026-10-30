const form = document.getElementById("search-form");
const queryInput = document.getElementById("query");
const submitButton = form.querySelector('button[type="submit"]');
const statusMessage = document.getElementById("search-status");
const resultList = document.getElementById("result-list");
const modeInputs = form.querySelectorAll('input[name="mode"]');
let latestRequestId = 0;
let activeRequest = null;

for (const input of modeInputs) {
    input.addEventListener("change", () => {
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
    });
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitButton.disabled) {
        return;
    }

    resultList.replaceChildren();
    statusMessage.textContent = "Searching...";
    resultList.setAttribute("aria-busy", "true");
    submitButton.disabled = true;

    const mode = form.querySelector('input[name="mode"]:checked').value;
    const parameters = new URLSearchParams({ q: queryInput.value, mode });
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
            item.textContent = mode === "phrase"
                ? result.filename
                : result.filename + " - Score: " + result.score.toFixed(2);
            resultList.appendChild(item);
        }
        statusMessage.textContent = data.results.length
            + (mode === "phrase" ? " phrase match(es) found." : " result(s) found.");
    } catch (error) {
        if (requestId === latestRequestId) {
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
