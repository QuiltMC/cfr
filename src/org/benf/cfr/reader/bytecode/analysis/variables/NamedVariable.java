package org.benf.cfr.reader.bytecode.analysis.variables;

import org.benf.cfr.reader.bytecode.analysis.types.MethodPrototype;
import org.benf.cfr.reader.util.output.Dumpable;
import org.benf.cfr.reader.util.output.Dumper;

public interface NamedVariable extends Dumpable {
    void forceName(String name);

    String getStringName();

    boolean isGoodName();

    @Override
    Dumper dump(Dumper d);

    Dumper dump(Dumper d, boolean defines);

    // fabric start
    default Dumper dumpParameter(Dumper d, MethodPrototype prototype, int index, boolean defines) {
        return dump(d, defines);
    }

    // start offset measured in instructions, not bytes
    default Dumper dumpLocalVariable(Dumper d, int localVarIndex, Ident ident, int tableIndex, int startOffset, boolean defines) {
        return dump(d, defines);
    }
}
