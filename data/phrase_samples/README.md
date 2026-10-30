# Fictional IIT phrase-search collection

These nine original documents are fictional demonstration data for Phase 3.2A.
They do not describe real IIT notices, policies, people, or schedules.

Index this folder explicitly with `buildIndex("data/phrase_samples")` to use it.
The normal `buildIndex("data")` loader reads immediate `.txt` files only, so
this nested collection does not enter the default CLI/server index. This README
is also excluded by the `.txt` filter.

| File | Scenario and useful distinction |
| --- | --- |
| `academic_announcement.txt` | Project presentation; `in the seminar room` includes stop words. |
| `algorithms_coursework.txt` | Binary search and running-time discussion; no Java topic. |
| `information_retrieval_workshop.txt` | Information retrieval, inverted index, and adjacent `java search`. |
| `java_programming_lab.txt` | `java and java search`, several `java search` occurrences, and repeated `error error`. |
| `networking_lab.txt` | Java and search separated by `for a simple`. |
| `project_presentation.txt` | Another project presentation, for checking filename order across matches. |
| `reverse_order_exercise.txt` | Adjacent `search java`, with the opposite order from `java search`. |
| `seminar_discussion.txt` | Programming in Java, with wording different from `java programming`. |
| `software_engineering_coursework.txt` | Software engineering and two separated occurrences of error. |

Examples checked by `tests/PhraseSearchTests.java` (run from the repository root):

- `java search`: `information_retrieval_workshop.txt`, `java_programming_lab.txt`.
- `project presentation` or `in the seminar room`: `academic_announcement.txt`,
  `project_presentation.txt`.
- `search java`: `reverse_order_exercise.txt`.
- `error error`: `java_programming_lab.txt`.
- `programming in java`: `seminar_discussion.txt`.

Results are matching documents in filename order, without phrase ranking.
