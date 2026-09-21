from ga_eval import text
from ga_eval.bm25 import Bm25Index, Chunk


def chunk(document: str, body: str) -> Chunk:
    return Chunk(document, 1, 0, len(body), body)


def test_tokens_drop_stopwords_and_share_stems_across_inflections():
    assert text.tokens("Which paid volunteer days do EU employees receive?") == text.tokens("paid volunteering day EU employee received")


def test_rare_terms_outweigh_common_ones():
    index = Bm25Index(
        [
            chunk("common", "The policy applies to every employee. The policy is reviewed yearly. The policy is owned by HR."),
            chunk("rare", "Volunteer days: employees receive two paid volunteer days."),
            chunk("filler", "The policy for expenses covers travel and meals."),
        ]
    )

    ranked = index.search("volunteer policy", k=3)

    assert ranked[0][0].document == "rare"


def test_chunks_without_any_query_term_are_not_returned():
    index = Bm25Index([chunk("a", "billing database failover"), chunk("b", "volunteer days")])

    assert [c.document for c, _ in index.search("volunteer", k=10)] == ["b"]


def test_ties_keep_corpus_order():
    index = Bm25Index([chunk("first", "volunteer days"), chunk("second", "volunteer days")])

    assert [c.document for c, _ in index.search("volunteer", k=2)] == ["first", "second"]
