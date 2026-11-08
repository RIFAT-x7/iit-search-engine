// Presentation only: offsets always point into the original source, never reconstructed text.
const HighlightPresentation = (() => {
    const stopWords = new Set(["a", "an", "the", "and", "or", "is", "are", "of", "to", "in", "for", "with"]);

    function tokens(text) {
        if (typeof text !== "string") { return null; }
        const lower = text.toLowerCase();
        // A length-changing Unicode case conversion cannot safely reuse source offsets.
        if (lower.length !== text.length) { return null; }
        const result = [];
        let start = -1;
        for (let i = 0; i <= lower.length; i++) {
            const code = lower.charCodeAt(i);
            const word = (code >= 97 && code <= 122) || (code >= 48 && code <= 57);
            if (word && start < 0) { start = i; }
            if (!word && start >= 0) {
                result.push({ term: lower.slice(start, i), start, end: i });
                start = -1;
            }
        }
        return result;
    }

    function booleanGroups(query) {
        if (typeof query !== "string" || !query.trim()) { return null; }
        const parts = query.trim().split(/[ \t\r\n\f\v]+/);
        if (parts.length % 2 === 0) { return null; }
        const groups = [[]];
        for (let i = 0; i < parts.length; i++) {
            const part = parts[i];
            const upper = part.toUpperCase();
            if (i % 2 === 1) {
                if (upper === "OR") { groups.push([]); }
                else if (upper !== "AND") { return null; }
            } else {
                if (["AND", "OR", "NOT"].includes(upper)
                        || [...part].some(c => '()[]{}"\'*?&|'.includes(c))) { return null; }
                const operand = tokens(part);
                if (!operand || operand.length !== 1 || stopWords.has(operand[0].term)) { return null; }
                groups[groups.length - 1].push(operand[0].term);
            }
        }
        return groups;
    }

    function ranges(text, query, mode) {
        const source = tokens(text);
        if (!source || typeof query !== "string" || !query.trim()) { return []; }
        const selected = new Set();
        if (mode === "phrase") {
            const phrase = tokens(query);
            if (!phrase || phrase.length === 0) { return []; }
            for (let i = 0; i <= source.length - phrase.length; i++) {
                if (phrase.every((word, offset) => word.term === source[i + offset].term)) {
                    for (let offset = 0; offset < phrase.length; offset++) { selected.add(i + offset); }
                }
            }
        } else {
            let terms;
            if (mode === "keyword") {
                const words = tokens(query);
                if (!words) { return []; }
                terms = new Set(words.filter(word => !stopWords.has(word.term)).map(word => word.term));
            } else if (mode === "boolean") {
                const groups = booleanGroups(query);
                if (!groups) { return []; }
                const present = new Set(source.map(word => word.term));
                terms = new Set();
                for (const group of groups) {
                    if (group.every(term => present.has(term))) {
                        for (const term of group) { terms.add(term); }
                    }
                }
            } else { return []; }
            source.forEach((word, i) => { if (terms.has(word.term)) { selected.add(i); } });
        }
        return source.filter((word, i) => selected.has(i)).map(word => ({ start: word.start, end: word.end }));
    }

    function splitsSurrogate(text, position) {
        const before = text.charCodeAt(position - 1);
        const after = text.charCodeAt(position);
        return before >= 0xd800 && before <= 0xdbff && after >= 0xdc00 && after <= 0xdfff;
    }

    function snippet(text, matches) {
        if (!matches.length) { return null; }
        let start = Math.max(0, Math.min(matches[0].start - 70, text.length - 200));
        let end = Math.min(text.length, start + 200);
        // Include entire matched tokens, even if an unusually long token exceeds the target.
        for (const match of matches) {
            if (match.start < start && match.end > start) { start = match.start; }
            if (match.start < end && match.end > end) { end = match.end; }
        }
        if (splitsSurrogate(text, start)) { start--; }
        if (splitsSurrogate(text, end)) { end++; }
        return {
            text: text.slice(start, end), start, end,
            leading: start > 0, trailing: end < text.length,
            ranges: matches.filter(match => match.start >= start && match.end <= end)
                .map(match => ({ start: match.start - start, end: match.end - start }))
        };
    }

    function render(container, text, matches, leading = false, trailing = false) {
        const document = container.ownerDocument;
        container.replaceChildren();
        if (leading) { container.appendChild(document.createTextNode("…")); }
        let cursor = 0;
        for (const match of matches) {
            if (match.start < cursor || match.end <= match.start || match.end > text.length) { continue; }
            container.appendChild(document.createTextNode(text.slice(cursor, match.start)));
            const mark = document.createElement("mark");
            mark.textContent = text.slice(match.start, match.end);
            container.appendChild(mark);
            cursor = match.end;
        }
        container.appendChild(document.createTextNode(text.slice(cursor)));
        if (trailing) { container.appendChild(document.createTextNode("…")); }
    }

    return { tokens, ranges, snippet, render };
})();

if (typeof module !== "undefined" && module.exports) { module.exports = HighlightPresentation; }
