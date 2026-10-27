const form = document.getElementById("search-form");
const queryInput = document.getElementById("query");
const submitButton = form.querySelector('button[type="submit"]');
const statusMessage = document.getElementById("search-status");
const resultList = document.getElementById("result-list");

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitButton.disabled) {
        return;
    }

    resultList.replaceChildren();
    statusMessage.textContent = "Searching...";
    resultList.setAttribute("aria-busy", "true");
    submitButton.disabled = true;

    const parameters = new URLSearchParams({ q: queryInput.value });
    try {
        const response = await fetch("/search?" + parameters.toString());
        const data = await response.json();
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
            item.textContent = result.filename + " - Score: " + result.score.toFixed(2);
            resultList.appendChild(item);
        }
        statusMessage.textContent = data.results.length + " result(s) found.";
    } catch (error) {
        statusMessage.textContent = "Unable to load search results. Check your connection and try again.";
    } finally {
        resultList.setAttribute("aria-busy", "false");
        submitButton.disabled = false;
    }
});
