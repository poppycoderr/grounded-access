"""Tokenization shared by the BM25 reference and the lexical-overlap check: lowercase words, English stopwords removed, Snowball-stemmed.

This approximates PostgreSQL's `english` text-search configuration closely enough for a reference ranker; it is not meant to match it exactly.
"""

import re
from functools import lru_cache

import snowballstemmer

_WORD = re.compile(r"[a-z0-9]+")
_STEMMER = snowballstemmer.stemmer("english")

_STOPWORD_TEXT = """
a about above after again against all am an and any are as at be because been before being below between both but by can could did
do does doing down during each few for from further had has have having he her here hers herself him himself his how i if in into
is it its itself just me more most my myself no nor not now of off on once only or other our ours ourselves out over own same she
should so some such than that the their theirs them themselves then there these they this those through to too under until up very
was we were what when where which while who whom why will with would you your yours yourself yourselves
"""
STOPWORDS = frozenset(_STOPWORD_TEXT.split())


@lru_cache(maxsize=65536)
def _stem(word: str) -> str:
    return _STEMMER.stemWord(word)


def tokens(text: str) -> list[str]:
    return [_stem(word) for word in _WORD.findall(text.lower()) if word not in STOPWORDS]
