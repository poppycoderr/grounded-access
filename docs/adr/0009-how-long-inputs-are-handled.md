# ADR-0009: How long inputs are handled

- Status: Accepted
- Date: 2026-10-20

## Context

Three limits govern how much text reaches a model, and nothing makes them agree:

| Layer | Limit | Unit |
|---|---|---|
| Ingestion API | 500,000 per document | characters |
| Chunker | 180 per chunk, but a sentence or a code block longer than that stays whole, and a run of characters without spaces counts as one word | words |
| Model service | 8,000 per text, else `422 TEXT_TOO_LONG` | characters |
| Models | 512 per input, including special tokens; for the reranker, query and passage together | tokens |

Two failures follow (review R11, open question Q14):

- **Ingestion failure.** A 10,000-character sentence becomes one 9,999-character chunk. The API accepts the document; the model service then rejects the chunk, and the job fails as `MODEL_SERVICE_REJECTED`, which names neither the document nor the cause.
- **Silent truncation.** Below 8,000 characters, fastembed truncates every input to 512 tokens without saying so. A long chunk is then represented by the vector of its first part only. The heading path added by embedding context (ADR-0007) makes the input longer still, and a long query pushes a reranker pair over 512 tokens.

Neither happens on the demo corpus: its largest embedding input is 79 tokens and its longest chunk 308 characters. Both happen as soon as someone ingests real documents with long code blocks, tables flattened into one line, or base64 data.

## Decision

### Tokens are the limit; characters and words only plan

The model's token limit is the only limit that decides what a vector represents. The chunker keeps planning in words, because that is what the dataset and the chunk-size settings are expressed in. It then checks every chunk against the model's tokenizer and splits what does not fit.

### The model service never truncates silently

- **Embedding.** The service counts the tokens of each input, prefix and special tokens included. An input over the model's limit is rejected with `422 TEXT_TOO_LONG`, giving its position in the request and its token count, never its text. Nothing is embedded from a truncated input.
- **Tokenizing.** A new endpoint, `POST /v1/tokenize`, returns for each text its token count and how many of its leading characters fit within a given token budget. The embedding model and the reranker have separate tokenizers, so both are applied and the stricter result counts: a chunk that fits is guaranteed to fit both models. It returns no tokens and no vectors, and it holds no identity, like the rest of the service.
- **Reranking.**
  - Passages are never truncated; the chunk budget below guarantees they fit.
  - The query is cut so that query, passage and the three special tokens fit in 512, which leaves it at least 125 tokens. The response says when that happened. The execution record stores it, so a reranked result with a cut query is visible.

### Chunks have a token budget

Chunker release 4 adds two rules to release 3:

1. **Character cap.** No piece is longer than 2,000 characters before tokenizing. A longer sentence, code block or space-free run is split before it is packed:
   - at the last line break within the cap;
   - else at the last whitespace;
   - else at the cap itself.

   The resulting pieces are contiguous spans of the document, so offsets still point into the text, and they get no overlap with each other.
2. **Token budget.** Every embedding input must fit in **384 tokens**. The input is the heading path, a blank line and the chunk. The remaining 128 are the reranker's room for the query and its separators. After packing, the control plane tokenizes all of a document's embedding inputs in one call. A chunk that does not fit is split where its fitting prefix ends, using the line break, whitespace, cap order above, and the remainder is checked again. Tokenizers are deterministic, so the same document always gets the same chunks.

- **Heading path.** What is embedded of it is limited to 200 characters. The outermost headings are dropped first, because the nearest heading says most about the chunk; a single heading longer than that is cut at the limit. The stored `section_path` is not changed.
- **Index profile.** The budget, the cap and the heading limit become part of the profile (ADR-0007), as `chunk_max_tokens`, `chunk_max_chars` and `heading_max_chars`. A tokenizer is part of the model revision that is already in the profile.

### Errors name the cause

- **Search queries.** A query whose embedding input exceeds the model's limit is the caller's error. A strategy with a dense channel answers `400 QUERY_TOO_LONG` with the limit in tokens. `sparse-only` is not affected.
- **Ingestion.** A document can always be chunked to fit, because pieces can be split down to single characters, so ingestion has no new length error. If the model service still rejects an input, that is a broken contract between the two services. The job fails with `MODEL_RESPONSE_INVALID` (ADR-0007), not as a rejected request.
- **API limits.** The 500,000-character document limit and the 8,000-character limit of the model service stay, as bounds on request size. A chunk is at most 2,000 characters plus a 200-character heading path, well under 8,000.

### Rebuild and evaluation

- **Rebuild.** Release 4 is a new index profile. As with every profile change, an existing index is rebuilt by loading the corpus into an empty database, and the control plane refuses to start on release 3 data until then (ADR-0007). It does not create document versions (ADR-0006).
- **Evaluation.** The budget is set by the models' limits, not tuned on any split, so there is no `dev` selection to make. On the demo corpus release 4 should produce exactly the chunks of release 3, since every input is far below the budget. The evidence is:
  - the chunk listings of both releases compared;
  - the committed keyword rankings, which must still match (ADR-0008);
  - a `test` run with no change in any metric.

  If any of these differs, the change is reported as a retrieval change and evaluated as such.
- **Tests.** Long inputs are covered by tests with synthetic documents, not by new dataset cases:
  - a 10,000-character sentence;
  - a long code block;
  - a 5,000-character space-free run;
  - a long heading path;
  - a chunk of short words that fits 180 words but not 384 tokens;
  - a query over the limit.

  Each has a fixed expected result: offsets that cover the text without gaps, every input within the budget, and the model service refusing what is over its limit.

## Consequences

- No input is embedded or reranked from a silently truncated text, and a long document no longer fails at the model service.
- Chunk boundaries now depend on the tokenizer as well as on word counts. That is deterministic and recorded in the index profile, but a model change can change chunking.
- Ingestion makes one more model-service call per document, to tokenize; it carries the same text the embedding call carries.
- Upgrading means reloading the corpus, as for any profile change.
- New: `POST /v1/tokenize`, token counts and refusal in `/v1/embed`, the rerank query cut and its report, chunker release 4, three profile fields, `400 QUERY_TOO_LONG`.
