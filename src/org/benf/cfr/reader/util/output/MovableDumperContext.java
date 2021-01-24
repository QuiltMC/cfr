package org.benf.cfr.reader.util.output;

// fabric: allow passing it onto child dumpers
public class MovableDumperContext {
    BlockCommentState inBlockComment = BlockCommentState.Not;
    boolean atStart = true;
    boolean pendingCR = false;
    int indent;
    int outputCount = 0;
    int currentLine = 1; // lines are 1 based.  Sigh.
}
