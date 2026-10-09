-- The reranker that scored the candidates of an execution, with its revision; null when the strategy does not rerank or reranking was unavailable.
alter table query_execution add column reranker_model text;
