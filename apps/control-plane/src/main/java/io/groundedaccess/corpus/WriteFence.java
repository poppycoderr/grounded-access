package io.groundedaccess.corpus;

/**
 * Lets the caller of an ingestion take part in the transaction that writes the document. {@link #verify()} runs first, before the document
 * row is locked, and aborts the transaction by throwing; {@link #record(IngestionResult)} runs last, with what was written. An ingestion job
 * uses it to check that its worker still owns the job and to move the job's checkpoint in the same commit as the document.
 */
public interface WriteFence {

    WriteFence NONE = new WriteFence() {

        @Override
        public void verify() {
        }

        @Override
        public void record(IngestionResult result) {
        }
    };

    void verify();

    void record(IngestionResult result);
}
