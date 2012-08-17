package org.benf.cfr.reader.bytecode.analysis.structured.statement;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifier;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.Vector;

/**
 * Created:
 * User: lee
 * Date: 15/05/2012
 */
public class UnstructuredSynchronized extends AbstractStructuredStatement {
    private Expression monitor;
    private BlockIdentifier blockIdentifier;

    public UnstructuredSynchronized(Expression monitor, BlockIdentifier blockIdentifier) {
        this.monitor = monitor;
        this.blockIdentifier = blockIdentifier;
    }

    @Override
    public void dump(Dumper dumper) {
        dumper.print("** synchronized (" + monitor.toString() + ")\n");
    }

    @Override
    public StructuredStatement claimBlock(Op04StructuredStatement innerBlock, BlockIdentifier blockIdentifier, Vector<BlockIdentifier> blocksCurrentlyIn) {
        if (blockIdentifier != this.blockIdentifier) {
            throw new RuntimeException("MONITOREXIT statement claiming wrong block");
        }

        return new StructuredSynchronized(monitor, blockIdentifier, innerBlock);
    }

    @Override
    public boolean isProperlyStructured() {
        return false;
    }
}