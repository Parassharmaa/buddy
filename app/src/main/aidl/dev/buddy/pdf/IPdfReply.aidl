package dev.buddy.pdf;
interface IPdfReply {
    void completed(String requestId, String text, int pagesRead, int totalPages, boolean truncated, int error);
}
