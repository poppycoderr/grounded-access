package io.groundedaccess.modelclient;

/**
 * Whether text is a search query or a corpus passage; the embedding model may encode the two differently.
 */
public enum InputType {
    QUERY,
    PASSAGE
}
