# Phase 1: Foundations

**Student:** Rifat Al Maruf (BSSE1705)

**Course:** SPL-1, Institute of Information Technology, University of Dhaka

**Record date:** October 26, 2026

## 1. Project and academic objective

IIT Search Engine is a Java project for searching an IIT-focused document
collection using classical Information Retrieval (IR). The academic objective
is to understand and implement preprocessing, indexing, retrieval, and ranking.
The core uses no Lucene, Elasticsearch, Solr, or other ready-made search engine.

Phase 1 established a small command-line prototype over fictional English text
files. The proposal describes a larger intended scope, including authorized IIT
PDFs, text extraction, and possible OCR. Those proposal items are plans, not
implemented capabilities. The supervisor's later client-server requirement
guides Phase 2; Phase 1 itself used a local CLI.

## 2. Git and GitHub foundations

Git records local project history. GitHub hosts a remote repository and makes
published commits available for review. A local commit does not publish itself.

| Term | Meaning in this project |
| --- | --- |
| Repository | Project files together with Git's history and metadata |
| Working tree | The checked-out files being inspected or edited |
| Staging area | The selected changes that will enter the next commit |
| Commit | A recorded project snapshot with a message and parent history |
| Branch | A movable name pointing to a commit; feature work can stay separate from `main` |
| HEAD | The currently checked-out commit, normally through a branch |
| Remote | A named repository address; `origin` points to this project's GitHub repository |
| Push | Sending local commits to a remote branch |
| Remote tracking branch | A local record such as `origin/main`; it can be stale |

Useful inspection commands include `git status`, `git diff`,
`git diff --cached`, and `git log`. Staging a specific path avoids accidentally
including unrelated files. `git diff --cached --check` checks staged whitespace
errors; it does not test program behavior.

A clean working tree means no pending changes reported by normal Git status.
Ignored outputs can still exist. In this repository, `.idea/`, `*.iml`, `*.class`,
and `out/` are ignored. A clean status alone does not prove that GitHub has the
same commit; remote verification is a separate check.

The completed Phase 1 test milestone was committed as
`ddea0671bedf6870e34d73f1ed357c184b187b5c` with the message
`test: add baseline regression test harness`.

## 3. Java environment and IntelliJ IDEA

The JDK contains the compiler (`javac`) and runtime launcher (`java`). Compilation
turns `.java` files into `.class` bytecode. The runtime loads that bytecode and
calls the selected class's `main()` method.

This project has a production source root named `src`, a separate `tests` folder,
and default-package classes. It has no Maven, Gradle, JUnit, or external runtime
dependency. Local IntelliJ metadata declares a Java module using SDK `25`,
language level `JDK_25`, and output under `out`.

Verified toolchain:

```text
C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot
javac 25.0.3
Temurin OpenJDK Runtime 25.0.3+9-LTS
```

The shell's plain `java` command was found through Oracle's `java8path`, while
`javac` and `JAVA_HOME` pointed to JDK 25. IntelliJ's project SDK, `JAVA_HOME`,
and executable selection through `PATH` are separate settings. Setting one does
not guarantee that another uses it. The checks used explicit paths to the same
JDK's compiler and runtime, without changing persistent environment settings.
Java 8 is unsuitable here: the source uses `Files.readString`, available since
Java 11, and the verified build uses JDK 25.

## 4. Controlled AI-assisted development

Codex assisted with inspection, test implementation, and verification under
explicit task boundaries. The workflow separated a read-only audit, approved
compilation, approved test development, and separately authorized Git publishing.
The baseline tests were reviewed against the actual source rather than a list
of desired features.

AI-generated explanations and code still require review. For this academic
project, the student should be able to explain each check, reproduce the
commands, and identify what the results do not prove. AI assistance does not
replace that understanding. These notes document the workflow; they do not
claim any personal learning experience on the student's behalf.

## 5. Original six classes

| Class | Responsibility |
| --- | --- |
| [Document](../src/Document.java) | Immutable integer ID and filename |
| [TextProcessor](../src/TextProcessor.java) | Lowercase, tokenize, and remove configured stop words |
| [InvertedIndex](../src/InvertedIndex.java) | Store term frequencies per document and token statistics |
| [SearchEngine](../src/SearchEngine.java) | Load files, build the index, retrieve, score, and sort |
| [SearchResult](../src/SearchResult.java) | Hold a document and its numeric score |
| [Main](../src/Main.java) | Run the console menu and display results |

The pipeline is:

```text
UTF-8 text files -> preprocessing -> frequency index
Query -> same preprocessing -> posting lookup -> scoring -> sorted results
```

## 6. Collection and text preprocessing

`buildIndex` reads immediate regular `.txt` files from a specified directory,
using UTF-8. It sorts filenames and assigns IDs starting at 1. The CLI supplies
the relative directory `data`, so its working directory must be the repository
root. Loading is not recursive and does not read PDFs.

`TextProcessor` lowercases using `Locale.ROOT`, scans ASCII letters and digits,
and treats punctuation and other characters as separators. For example,
`Java,SEARCH 25` becomes `java`, `search`, `25`. The 12 stop words are:

```text
a an the and or is are of to in for with
```

Repeated document terms are retained for frequency counting. There is no
stemming, lemmatization, or general Unicode word support. IDs are stable only
for an unchanged sorted collection; adding a filename can shift later IDs.
`Document` stores a filename rather than a complete source path.

## 7. Inverted index and term frequency

The index uses a nested map:

```text
term -> (document ID -> occurrence count)
java -> {1=2, 2=1}
```

This example means that `java` occurs twice in document 1 and once in document
2. Term frequency (TF) is that raw occurrence count. Document frequency (df) is
the number of documents containing the term, which is 2 in the example.

The index counts distinct retained terms and all retained token occurrences.
It does not store token positions. An index build uses fresh temporary
structures and replaces the old structures only after file loading succeeds.
Calling `InvertedIndex.addDocument` twice directly would accumulate counts;
`SearchEngine.buildIndex` avoids that during a full rebuild.

## 8. Query processing and actual ranking

The engine processes query text with the same `TextProcessor`. A
`LinkedHashSet` removes repeated query terms so repetition does not add weight.
It looks up each term and adds contributions to matching document scores.
Ordinary multi-word retrieval has OR semantics: matching any retained term is
enough to become a candidate.

For each distinct processed query term, the implemented formula is:

```text
idf(term) = ln((N + 1) / (df(term) + 1)) + 1
score(document, query) = sum of tf(term, document) * idf(term)
```

`N` is the number of loaded documents and `ln` is the natural logarithm.
This is a smoothed TF-IDF-style score with raw TF. It does not use document-length
normalization or cosine similarity. Longer or repetitive documents can receive
higher scores. A term present in every document has IDF 1 under this formula.

Positive-score results are sorted by descending score using insertion sort.
Equal scores retain the initial filename order. All matches are returned;
there is no Top-K limit. The CLI rounds displayed scores to two decimal places.

`AND` and `OR` are currently removed as stop words, not parsed as operators.
Quotes do not create phrase queries. Boolean parsing and phrase search remain
planned. Future phrase support must preserve enough position information to
avoid collapsing gaps left by removed stop words.

## 9. CLI behavior

At startup, `Main` attempts to build the index and prints a fictional-data
notice. The menu supports full build/rebuild, search, index statistics, and exit.
It handles invalid menu choices, end-of-input, and build `IOException`s.
A failed rebuild keeps any previously built index. A failed initial build has
no earlier index to preserve.

## 10. Baseline testing

[BaselineTests.java](../tests/BaselineTests.java) is a normal Java program with
seven named tests. A helper throws `AssertionError` explicitly on failed checks;
it does not rely on Java's optional `assert` statements. The runner reports each
test, totals the results, and exits with status 1 if a test fails.

| Test | What it checks |
| --- | --- |
| Text preprocessing | Case, punctuation, all configured stop words, numeric tokens |
| Inverted-index correctness | Exact postings, frequencies, vocabulary size, token count |
| Keyword retrieval | Exactly the two expected matching filenames |
| Ranking | Higher TF ranks first; expected scores within `1e-9` |
| Empty and unknown queries | Empty, whitespace, unknown, and stop-word-only queries |
| Rebuild correctness | Building twice preserves counts, result order, and scores |
| Failed rebuild preservation | Missing-folder `IOException` and continued use of the prior index |

Collection tests create their own temporary directories and tiny UTF-8 files.
They clean up only their named fixtures and owned directories using
try-with-resources. They never replace the original `data` files. The ranking
fixture deliberately places the frequent-term document last alphabetically so
filename order alone cannot make the ranking test pass.

## 11. Verified observations

Phase 1 smoke testing and a fresh check on October 26, 2026 produced:

| Observation | Result |
| --- | --- |
| Loaded sample documents | 6 |
| Unique processed terms | 75 |
| Processed tokens | 120 |
| First result for `java` | `java_programming.txt`, displayed score 5.54 |
| Second result for `java` | `academic_notice.txt`, displayed score 1.85 |
| Baseline tests | 7 passed, 0 failed; exit code 0 |
| Compilation | Successful with explicit JDK 25.0.3 |
| CLI termination | Normal `Goodbye!`, exit code 0 |

The sample-corpus statistics come from the CLI smoke test, not the tiny test
fixtures. Passing seven tests establishes a baseline for the checked cases;
it does not prove all inputs, ranking quality, scalability, or future features.

## 12. Challenges and lessons supported by the work

- A mismatched shell runtime can undermine an otherwise correct SDK setup;
  verify both executables explicitly.
- A feature in a proposal or README is not implementation evidence; inspect
  source and execute a relevant check.
- Query normalization can remove syntax such as Boolean operators; parse future
  query structure before discarding important tokens.
- Rebuilding in temporary structures protects an existing usable index from
  ordinary file-read failures.
- Small deterministic fixtures make expected results and arithmetic explainable.
- Keep commits focused, inspect staged changes, and verify each push separately.

## 13. Viva preparation

1. **Why use an inverted index?** It maps a term directly to documents containing
   it, avoiding rereading every document for each keyword query.
2. **How do TF and df differ?** TF counts a term within one document; df counts
   documents containing that term.
3. **Is this cosine similarity?** No. The engine sums raw TF times smoothed IDF
   without vector or length normalization.
4. **Does `java AND network` require both words?** No. `and` is removed and the
   remaining words use the existing ranked OR retrieval.
5. **Why use explicit checks instead of `assert`?** Explicit checks always run;
   Java assertions require enabling them with `-ea`.
6. **What does a clean Git status prove?** It shows no reported pending changes;
   it does not independently prove the remote is current or the code is correct.

## 14. Student reflection — complete personally

Do not treat these prompts as completed reflections.

- Which class can I now explain without reading the code? Give an example.
- Calculate one TF-IDF score by hand and compare it with a test result.
- Which environment or Git issue did I personally encounter, and how did I verify it?
- Which baseline test was hardest to understand, and why?
- What would I change next, and which existing behavior must stay the same?

Return to the [learning index](README.md) or [project overview](../README.md).
